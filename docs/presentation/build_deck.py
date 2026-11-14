"""Build an editable Korean PowerPoint and a layout contact sheet.

Run with Python 3 and python-pptx / Pillow installed:
    python docs/presentation/build_deck.py

The PNG is a layout preview generated from the same drawing instructions,
not a screenshot produced by PowerPoint. All slide elements remain editable.
"""
from pathlib import Path
import json
import math
import re

from PIL import Image, ImageDraw, ImageFont
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.util import Inches, Pt
from pptx.oxml.xmlchemy import OxmlElement

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent
FONT_PATH = '/System/Library/Fonts/AppleSDGothicNeo.ttc'
if not Path(FONT_PATH).exists():
    raise RuntimeError('Set FONT_PATH to an installed Korean font before rebuilding.')
FONT = 'Apple SD Gothic Neo'
NAVY, TEAL, ORANGE = '#142C43', '#087F8C', '#D66B35'
BG, WHITE, INK, MUTED, LINE = '#F6F8FA', '#FFFFFF', '#173249', '#576A7A', '#DEE5EA'
SCALE = 120  # preview pixels per inch
prs = Presentation()
prs.slide_width, prs.slide_height = Inches(13.333333), Inches(7.5)
prs.core_properties.title = 'NOSPO 프로젝트 구조와 단일 모듈 비교'
prs.core_properties.subject = '현재 저장소 소스 기반 멀티모듈 학습 자료'
prs.core_properties.author = 'NOSPO'
slides_data, preview_pages, checks = [], [], []
current = None


def rgb(h):
    return RGBColor.from_string(h.lstrip('#'))


def rect(x, y, w, h, fill=WHITE, stroke=None, radius=False):
    shape = current['slide'].shapes.add_shape(
        MSO_SHAPE.ROUNDED_RECTANGLE if radius else MSO_SHAPE.RECTANGLE,
        Inches(x), Inches(y), Inches(w), Inches(h))
    shape.fill.solid()
    shape.fill.fore_color.rgb = rgb(fill)
    if stroke:
        shape.line.color.rgb = rgb(stroke)
    else:
        shape.line.fill.background()
    if radius:
        shape.adjustments[0] = 0.08
    box = tuple(round(v * SCALE) for v in (x, y, x + w, y + h))
    if radius:
        current['draw'].rounded_rectangle(box, radius=12, fill=fill, outline=stroke)
    else:
        current['draw'].rectangle(box, fill=fill, outline=stroke)
    return shape


def wrap(text, size, width):
    f = ImageFont.truetype(FONT_PATH, round(size / 72 * SCALE))
    limit = width * SCALE
    lines = []
    for raw in text.split('\n'):
        line = ''
        for c in raw:
            if f.getlength(line + c) > limit and line:
                lines.append(line.rstrip())
                line = c.lstrip()
            else:
                line += c
        lines.append(line)
    return lines, f


def text(x, y, w, h, value, size=18, color=INK, bold=False):
    lines, f = wrap(value, size, w - .05)
    leading = size * 1.24
    needed = len(lines) * leading / 72 + .04
    minimum = min(size, 13 if size < 25 else 24)
    while needed > h + .025 and size > minimum:
        size -= .5
        lines, f = wrap(value, size, w - .05)
        leading = size * 1.24
        needed = len(lines) * leading / 72 + .04
    if needed > h + .025:
        raise ValueError(f'Slide {len(prs.slides)} text overflow: {value[:65]} need={needed:.2f} h={h}')
    if x < 0 or y < 0 or x + w > 13.34 or y + h > 7.51:
        raise ValueError(f'Out of bounds: {value[:50]}')
    tb = current['slide'].shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tb.text_frame
    tf.word_wrap = False
    tf.margin_left = tf.margin_right = 0
    tf.margin_top = tf.margin_bottom = 0
    for i, line in enumerate(lines):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.text = line
        p.font.name, p.font.size, p.font.bold = FONT, Pt(size), bold
        p.font.color.rgb = rgb(color)
        p.line_spacing = Pt(leading)
        p.space_before = p.space_after = Pt(0)
        for run in p.runs:
            rpr = run._r.get_or_add_rPr()
            ea = OxmlElement('a:ea')
            ea.set('typeface', FONT)
            rpr.append(ea)
    for i, line in enumerate(lines):
        current['draw'].text((round(x*SCALE), round((y+i*leading/72)*SCALE)), line, font=f, fill=color)
    current['content'].append(value)
    checks.append({'slide': len(prs.slides), 'lines': len(lines), 'size': size, 'height': h})
    return tb


def new(chapter, title, subtitle='', sources=(), note=''):
    global current
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    img = Image.new('RGB', (1600, 900), BG)
    current = {'slide': slide, 'image': img, 'draw': ImageDraw.Draw(img), 'content': [], 'sources': list(sources)}
    slide.background.fill.solid()
    slide.background.fill.fore_color.rgb = rgb(BG)
    rect(0, 0, .13, 7.5, TEAL)
    text(.5, .30, 11.9, .3, chapter.upper(), 11, TEAL, True)
    text(.5, .80, 12.2, .66, title, 28, NAVY, True)
    if subtitle:
        text(.52, 1.52, 12, .55, subtitle, 14, MUTED)
    rect(.5, 6.94, 12.3, .012, LINE)
    text(.52, 7.06, 11.65, .24, 'NOSPO  /  현재 소스 기준  ·  2026.11.08  /  출처 경로는 발표자 노트 참고', 9, MUTED)
    text(12.25, 7.02, .55, .31, f'{len(prs.slides):02}', 13, TEAL, True)
    for s in sources:
        if not (ROOT / s).exists():
            raise ValueError(f'Missing source: {s}')
    slide.notes_slide.notes_text_frame.text = (
        note + '\n\n근거 파일/디렉터리 (저장소 루트 기준):\n' + '\n'.join(sources)
        + '\n\n화면 텍스트:\n')
    slides_data.append(current)
    return current


def card(x, y, w, h, label, body, accent=TEAL, size=18):
    rect(x, y, w, h, WHITE, LINE, True)
    rect(x, y, .055, h, accent)
    text(x+.22, y+.2, w-.44, .5, label, 19, accent, True)
    text(x+.22, y+.87, w-.44, h-1.02, body, size)


def takeaway(value):
    rect(.53, 6.12, 12.25, .57, NAVY, radius=True)
    text(.76, 6.23, 11.8, .36, value, 15, WHITE)


def compare(title, left, right, bottom, sources=(), note='', chapter='02 / 단일 모듈과 비교'):
    new(chapter, title, '단일 모듈 예시는 이해를 위한 일반적인 구성입니다. 과거 저장소를 복원한 것이 아닙니다.', sources, note)
    card(.53, 2.18, 5.96, 3.65, '일반적인 단일 모듈', left, MUTED, 19)
    card(6.77, 2.18, 6.0, 3.65, '현재 NOSPO 멀티모듈', right, TEAL, 19)
    takeaway(bottom)


def table(headers, rows, widths=None, y=2.16, row_h=.56, font=15):
    widths = widths or [12.25/len(headers)]*len(headers)
    xpos = .53
    for label, width in zip(headers, widths):
        rect(xpos, y, width, .48, NAVY)
        text(xpos+.13, y+.1, width-.24, .32, label, 13, WHITE, True)
        xpos += width
    ypos = y + .48
    for n, row in enumerate(rows):
        xpos = .53
        for value, width in zip(row, widths):
            rect(xpos, ypos, width, row_h-.018, WHITE if n % 2 == 0 else '#EAF0F4')
            text(xpos+.13, ypos+.1, width-.26, row_h-.14, value, font)
            xpos += width
        ypos += row_h
    if ypos > 6.88:
        raise ValueError(f'Table too tall {ypos}')


def flow(labels, descriptions, bottom, colors=None):
    n = len(labels)
    gap = .32
    width = (12.25-(n-1)*gap)/n
    for i, (label, body) in enumerate(zip(labels, descriptions)):
        x = .53 + i*(width+gap)
        c = (colors or [TEAL]*n)[i]
        card(x, 2.35, width, 2.95, label, body, c, 16)
        if i < n-1:
            text(x+width+.035, 3.52, .3, .5, '→', 20, TEAL)
    takeaway(bottom)


M = 'modules/'
def java(work, layer, name, package=None):
    package = package or {'adapter-web':'web','adapter-persistence':'persistence','adapter-integration':'integration','adapter-worker':'worker'}.get(layer, layer)
    base = 'lifecycle' if work == 'account-lifecycle' else work
    return f'modules/{work}/{layer}/src/main/java/kr/nospo/{base}/{package}/{name}.java'


new('NOSPO / CODE STUDY', '프로젝트 구조를 이해하는 멀티모듈 수업',
    '전체 디렉터리의 역할  ·  단일 모듈과 1:1 대조  ·  실제 회원가입 흐름',
    ['settings.gradle','build.gradle','modules','src','docs','erd'],
    '현재 작업 디렉터리의 소스와 설정을 기준으로 작성했다. 단일 모듈 쪽의 폴더와 코드는 비교를 위한 예시이며 저장소의 과거 구현이라고 주장하지 않는다. 라이브러리 사용법이나 최신 버전을 조사하는 자료가 아니라 현재 코드의 구조를 학습하는 자료다.')
text(.58, 2.4, 11.5, 1.45, '같은 Controller · Service · DB 코드가\n왜 여러 폴더와 모듈로 나뉘었을까?', 32, NAVY, True)
card(.58, 4.34, 3.85, 1.45, '50개 Java 파일', '루트 실행·설정·테스트 + 업무 구현', size=13)
card(4.65, 4.34, 3.85, 1.45, '22개 코드 하위 모듈', 'support와 역할별 Gradle 라이브러리', size=13)
card(8.72, 4.34, 4.02, 1.45, '하나의 실행 앱', 'Spring Boot 서버에 모두 조립', size=13)

new('01 / 큰 그림', '먼저 구분할 세 가지: 폴더 · 모듈 · 서버',
    '모듈을 나누었다고 서버나 DB가 자동으로 나뉘지는 않습니다.', ['settings.gradle','build.gradle','src/main/java/kr/nospo/nospo_backend/NospoBackendApplication.java'])
flow(['패키지 / 폴더','Gradle 모듈','실행 / 배포'],
     ['코드의 이름과 위치\nkr.nospo.member.*', '컴파일과 의존성 경계\n:member:application', '현재 루트 Spring Boot 앱 하나\nAPI와 메일 스케줄러 함께 실행'],
     '현재 구조: 업무 경계를 가진 하나의 애플리케이션, 즉 모듈러 모놀리스')

new('01 / 큰 그림', '저장소 최상위 디렉터리 전체 지도', '소스·설정·설계 자료와 도구 생성 폴더를 구분해서 읽습니다.', ['.gitignore','README.md','HELP.md','gradle','docs','erd','src','modules'])
table(['위치','역할','공부할 때 보는 것'],[
    ['src/','실행·공통 설정·리소스·통합 테스트','시작점, 보안, DB 스키마, 테스트'],
    ['modules/','업무별 / 역할별 Java 라이브러리','회원·인증·약관·보상·가입 조합'],
    ['docs/ · erd/','규칙·API 명세·요약표·ERD·학습 자료','계획과 현재 구현의 차이'],
    ['gradle/ · gradlew*','Gradle Wrapper와 실행 스크립트','저장소에 지정된 Gradle로 실행'],
    ['settings.gradle · build.gradle','모듈 등록 / 빌드·의존성·실행 앱 조립','어떤 코드가 무엇을 참조하는가'],
    ['.git/ · .idea/ · .gradle/ · build/','버전 관리 / IDE / 캐시 / 빌드 결과','업무 코드가 아님, 생성물 별도 취급'],
    ['README.md · HELP.md · .gitignore','실행 안내 / 참고 링크 / 추적 제외','실행법은 README와 실제 설정 확인'],
], [3.3,3.7,5.25], row_h=.55, font=14)

new('01 / 큰 그림', '두 축으로 읽으면 긴 경로도 해석된다', 'modules/identity/adapter-web/src/main/java/.../EmailVerificationController.java', ['modules'])
card(.53, 2.25, 5.96, 3.28, '첫 번째 축: 어느 업무인가?', 'member: 프로필·상태\nidentity: 계정·인증·세션\nterms: 약관·동의\ncredit: 보상\naccount-lifecycle: 가입 전체 순서', size=19)
card(6.77, 2.25, 6, 3.28, '두 번째 축: 어떤 역할인가?', 'contract: 외부에 공개할 기능\ndomain: 업무 규칙\napplication: 기능 수행 순서·포트\nadapter-*: HTTP·DB·외부 기술·스케줄\nsupport: 여러 업무의 기술 지원', size=19)
takeaway('identity / adapter-web = 인증 업무의 HTTP 입구')

compare('폴더를 나누는 방식이 어떻게 달라졌나?',
    'src/main/java/.../\n  controller/\n  service/\n  repository/\n  dto/\n  config/\n\n한 build.gradle 안에서 컴파일',
    'modules/{업무}/{역할}/\n  contract/\n  domain/\n  application/\n  adapter-web/\n  adapter-persistence/\n\n역할별 build.gradle로 경계 표현',
    '단일 모듈도 업무별 패키지 구성이 가능하다. 차이는 Gradle 의존성 경계를 추가한 점이다.',
    ['settings.gradle','modules/member/application/build.gradle'])

new('02 / 단일 모듈과 비교', '익숙한 이름과 현재 모듈의 1:1 대응표', '기존 개념은 유지되고, 업무 소유권과 연결 인터페이스가 더 명확해졌습니다.', ['modules'])
table(['단일 모듈에서 흔한 위치','현재 역할 / 예시','주의할 차이'],[
    ['controller/','adapter-web / SignupController','HTTP 입력·출력 담당'],
    ['service/','application / SignupService','업무별 기능과 전체 조합 분리'],
    ['repository/','adapter-persistence / JdbcMembers','현재는 JPA가 아니라 JDBC 구현'],
    ['dto/','web Request · Command · contract record','DTO 전체가 contract인 것은 아님'],
    ['validator/ · model/','domain / MemberInput · EmailAddress','현재 domain은 정규화·검증 위주'],
    ['client/ · mail/ · scheduler/','integration / worker 어댑터','외부 기술과 실행 입구 분리'],
    ['common/ · config/','support / 루트 config','기술 공통과 앱 조립 설정 구분'],
], [3.15,5.25,3.85], row_h=.55, font=14)

compare('같은 실행 방식, 더 명시적인 코드 의존성',
    '하나의 Spring Boot 앱\n하나의 DB 사용 가능\nService끼리 Java 메서드 호출\n\n패키지 규칙만으로 경계를 지키는 경우가 많음',
    '하나의 Spring Boot 앱\n현재 하나의 DB 사용\ncontract를 통한 Java 메서드 호출\n\nGradle 모듈 의존성으로 참조 범위를 표현',
    '멀티모듈은 마이크로서비스 전환이나 네트워크 통신 추가를 의미하지 않는다.',
    ['build.gradle','src/main/resources/application.properties'])

new('03 / 빌드와 조립', 'settings.gradle은 모듈 이름과 실제 폴더의 주소록', '논리적 Gradle 경로와 Java 패키지 이름은 서로 다른 개념입니다.', ['settings.gradle'])
card(.53, 2.2, 7.1, 2.8, '실제 설정의 의미', "include ':member:contract'\n\nproject(':member:contract').projectDir\n    = file('modules/member/contract')", size=18)
card(7.9, 2.2, 4.87, 2.8, '읽는 순서', '① 프로젝트 등록\n② 디렉터리 매핑\n③ 해당 build.gradle 확인\n④ 의존하는 모듈 따라가기', size=18)
takeaway('member 같은 상위 프로젝트도 등록되지만, 실제 코드는 주로 역할별 하위 모듈에 있다.')

new('03 / 빌드와 조립', 'build.gradle은 무엇을 가져다 쓰는지 명시한다', 'member/application/build.gradle의 실제 의존성 예시', ['build.gradle','modules/member/application/build.gradle'])
card(.53, 2.2, 6.1, 3.45, '하위 모듈', "api project(':member:contract')\nimplementation project(':member:domain')\nimplementation '...:spring-context'\n\napi: 사용하는 쪽에도 컴파일 의존성 공개\nimplementation: 전이 컴파일 공개 제한", size=17)
card(6.9, 2.2, 5.87, 3.45, '루트 실행 앱', 'Spring Boot 플러그인 적용\n필요한 업무 모듈을 모두 의존성으로 추가\n하위 모듈은 java-library\nJava 21 · 공통 라이브러리 버전 관리\n\nbootJar로 실행할 앱에 모듈을 함께 포함', size=17)
takeaway('implementation도 런타임에는 필요하다. api 전파 때문에 경계 규칙이 완벽히 강제되지는 않는다.')

new('03 / 빌드와 조립', 'Gradle은 코드 연결, Spring은 객체 연결', 'NospoBackendApplication의 scanBasePackages = "kr.nospo"가 모듈의 Bean을 함께 검색합니다.', ['src/main/java/kr/nospo/nospo_backend/NospoBackendApplication.java',java('member','application','MemberService'),java('member','adapter-persistence','JdbcMembers')])
flow(['클래스패스 구성','컴포넌트 검색','생성자 주입'], ['루트 build.gradle이\n라이브러리 모듈을 포함', '@Service / @Repository\n@Component를 발견', 'Members → MemberService\nMemberStore → JdbcMembers'], '컴파일 가능한 것과 실행할 구현체가 선택되는 것은 서로 다른 단계다.')

new('04 / 업무별 해설', '전체 업무 모듈: 소유하는 데이터로 이해하기', '누가 데이터를 책임지고, 다른 업무에 어떤 기능을 공개하는지 확인합니다.', ['modules','src/main/resources/db/migration/V1__email_signup.sql'])
table(['업무','책임 / 데이터','외부에 공개하는 기능'],[
    ['member','닉네임·전화번호·프로필·회원 상태','Members'],
    ['identity','이메일·비밀번호·인증·세션·메일 작업','SignupIdentity'],
    ['terms','약관 버전·시행·회원 동의','Terms'],
    ['credit','가입 지갑·보상 원장','SignupCredits'],
    ['account-lifecycle','가입 전체 진행·멱등 결과','현재 별도 contract 없음'],
    ['support','응답·오류·암호화·잠금·요청 제한','업무 모델이 아닌 기술 지원'],
], [2.35,6.0,3.9], row_h=.59, font=16)

compare('member: 평범한 회원 Service를 쪼개서 보면',
    'MemberController / MemberService\nMemberRepository / MemberDto\n\nService가 검증하고 저장소 호출\n프로필과 상태를 조회·변경',
    'contract / Members, Profile\napplication / MemberService, MemberStore\ndomain / MemberInput\npersistence / JdbcMembers\n\n현재 독립적인 member 웹 모듈은 없음',
    '이메일·비밀번호는 identity가 소유하고, member는 닉네임·전화번호·회원 상태를 맡는다.',
    [java('member','contract','Members'),java('member','application','MemberService'),java('member','domain','MemberInput')],chapter='04 / 업무별 해설')

new('04 / 업무별 해설', 'member.create: 실제 코드에서 보는 네 단계', '회원 생성 한 가지 기능만 따라가면 전체 구조의 기본형을 볼 수 있습니다.', [java('member','contract','Members'),java('member','application','MemberService'),java('member','application','MemberStore'),java('member','adapter-persistence','JdbcMembers')])
flow(['Members','MemberService','MemberStore','JdbcMembers'], ['다른 업무가 보는 공개 기능\ncreate / get / active', 'MemberInput으로 검증\n정규화 후 저장 요청', 'application이 요구하는\n저장 기능 인터페이스', 'JdbcTemplate으로\nmember INSERT / SELECT'], '실행 호출은 저장소로 향하지만, 소스에서 JdbcMembers가 MemberStore에 의존한다.')

new('04 / 업무별 해설', 'contract와 Store는 왜 인터페이스가 둘인가?', '두 인터페이스가 대답하는 질문이 다릅니다.', [java('member','contract','Members'),java('member','application','MemberStore')])
card(.53, 2.2, 5.96, 3.35, 'Members: 업무의 공개 입구', '누가 사용하나?\n→ 다른 업무의 서비스\n\n어떤 약속인가?\n→ 회원을 만들거나 프로필을 조회한다.\n\n구현체: MemberService', size=18)
card(6.77, 2.2, 6, 3.35, 'MemberStore: 저장 기능의 포트', '누가 사용하나?\n→ MemberService\n\n어떤 약속인가?\n→ 필요한 데이터를 저장·조회한다.\n\n구현체: JdbcMembers', size=18)
takeaway('contract는 업무 간 계약, Store는 업무 내부에서 필요로 하는 저장 연결 지점이다.')

compare('identity: 인증 업무와 외부 기술을 분리한다',
    'AuthController / AuthService\nMailService / JwtProvider\nAccountRepository\n\n인증·메일·암호화가 한 서비스에\n함께 들어가기 쉬움',
    'web / EmailVerificationController\napplication / 인증·발송·가입 identity 서비스\ncontract / SignupIdentity\ndomain / EmailAddress\npersistence / JdbcIdentityStore\nintegration / SMTP·JWT / worker / 스케줄',
    '현재 13개 Java 파일: 인증 순서, DB 구현, SMTP와 암호 기술을 각각 추적할 수 있다.',
    ['modules/identity'],chapter='04 / 업무별 해설')

new('04 / 업무별 해설', 'identity 내부를 실제 클래스 단위로 나누기', 'application은 해야 할 일을 정하고, 어댑터는 기술적인 수행 방법을 제공합니다.', ['modules/identity'])
table(['클래스 / 인터페이스','현재 역할'],[
    ['EmailVerificationService','이메일 정규화·요청 제한·재발송·링크 확인·proof 발급'],
    ['SignupIdentityService','가입 증명 검사·계정 생성·세션 발급·활성 세션 확인'],
    ['EmailDeliveryService','발송 작업 점유·전송·재시도·완료·만료 정리'],
    ['IdentityStore / JdbcIdentityStore','계정·인증·메일 작업의 저장 포트 / SQL 구현'],
    ['EmailSender / SmtpEmailSender','메일 발송 포트 / SMTP 구현과 실패 분류'],
    ['IdentityCrypto / JwtIdentityCrypto','해시·토큰 포트 / Argon2와 HS256 JWT 구현'],
    ['EmailWorker / EmailAddress','스케줄 진입점 / 이메일 정규화·검증'],
], [4.85,7.4], row_h=.55, font=14)

compare('terms와 credit: 작은 업무도 소유권을 갖는다',
    'TermsService / ConsentRepository\nCreditService / WalletRepository\n\nSignupService에서 다른 테이블을\n직접 수정하는 코드가 생길 수 있음',
    'terms: Terms → TermService\n→ TermStore → JdbcTerms\nTermsController는 조회 HTTP 입구\n\ncredit: SignupCredits → SignupCreditService\n→ CreditStore → JdbcCredits',
    '약관 동의 검증은 terms, 5크레딧 지급은 credit가 책임진다.',
    ['modules/terms','modules/credit'],chapter='04 / 업무별 해설')

compare('account-lifecycle: 가입 전체를 조율하는 곳',
    '하나의 SignupService에서\n회원 저장 + 계정 저장 + 동의 저장\n+ 지갑 저장 + 토큰 발급\n\n업무별 Repository를 직접 조합하기 쉬움',
    'SignupController: HTTP 입출력\nSignupService: 전체 순서와 트랜잭션\nSignupResult: 응답 데이터\nSignupRecords: 멱등 기록 포트\nJdbcSignupRecords: 결과 SQL 저장\n\n다른 업무는 contract로 호출',
    '진행 책임은 lifecycle에, 실제 데이터 변경 책임은 각 업무 모듈에 둔다.',
    ['modules/account-lifecycle'],chapter='04 / 업무별 해설')

new('04 / 업무별 해설', 'support와 루트 config는 역할이 다르다', '공통 기술 기능과 애플리케이션 조립 설정을 구분합니다.', ['modules/support','src/main/java/kr/nospo/nospo_backend/config'])
table(['위치','클래스','역할'],[
    ['support','ApiResponse · ApiException · ApiErrors','성공 응답, 업무 오류, 예외→HTTP 변환'],
    ['support','Secrets · SqlIds · DatabaseGuard','암호·지문, UUID·시각 변환, 잠금·제한'],
    ['root/config','AuthConfiguration','Clock·JWT encoder/decoder·스케줄 활성화'],
    ['root/config','SecurityConfig','인증 경로·CORS·Origin·오류 응답'],
    ['root/config','OpenApiConfig','Swagger 문서·Bearer 인증 표시'],
    ['root/config','SignupCleanup','만료한 가입 멱등 기록 정리'],
], [2.2,5.05,5], row_h=.59, font=14)

new('05 / 요청 흐름', '실제 HTTP API는 세 Controller에서 시작한다', '명세에 있는 모든 API가 구현된 것은 아닙니다.', [java('account-lifecycle','adapter-web','SignupController'),java('identity','adapter-web','EmailVerificationController'),java('terms','adapter-web','TermsController')])
table(['Controller','구현 경로 (기본 /api/v1)','내부 진입점'],[
    ['TermsController','GET /terms · GET /terms/{id}','Terms'],
    ['EmailVerificationController','GET /auth/availability/email','EmailVerificationService'],
    ['EmailVerificationController','POST /auth/signup/email-verifications','request'],
    ['EmailVerificationController','POST .../email-verifications/confirm','confirm'],
    ['SignupController','GET /auth/availability/nickname','SignupService'],
    ['SignupController','POST /auth/signup · GET /me','signup · me'],
], [3.5,5.5,3.25], row_h=.59, font=13)

new('05 / 요청 흐름', '이메일 인증 → 가입 증명 → 가입 완료', '메일의 token, 가입용 emailProof, 인증용 Access Token은 용도가 다릅니다.', [java('identity','application','EmailVerificationService'),java('account-lifecycle','application','SignupService')])
flow(['메일 요청','링크 확인','가입 완료','내 정보 조회'], ['인증 요청 + 발송 작업 저장\n202 접수 응답', '메일 token을 POST\nemailProof 발급', 'emailProof + 가입 정보\nAccess JWT + Refresh 쿠키', 'Bearer Access Token\nGET /api/v1/me'], '메일 token과 emailProof는 각각 발급 후 10분. Access Token은 15분이다.')

new('05 / 요청 흐름', '메일 발송: HTTP 요청과 실제 전송을 분리', '현재 워커는 별도 서버가 아니라 같은 Spring Boot 앱의 스케줄러입니다.', [java('identity','application','EmailDeliveryService'),java('identity','adapter-worker','EmailWorker'),java('identity','adapter-integration','SmtpEmailSender')])
flow(['요청 트랜잭션','DB outbox','워커 점유','SMTP / 결과'], ['인증 요청 생성\n암호화된 발송 자료 저장', 'email_delivery\n전송할 일을 지속 보관', '기본 3초 fixed delay\n점유·임대 확인', '트랜잭션 밖에서 전송\n완료·재시도 별도 기록'], '202 = 작업 접수, ACCEPTED = 제공자 접수. 받은편지함 도착 보장은 아니다.')
current['slide'].notes_slide.notes_text_frame.text += '\n발송은 최대 5회이며 일시 오류에 재시도한다. 제공자가 접수한 직후 프로세스가 중단되면 중복 발송 가능성이 있다. 정확히 한 번 도착한다고 설명하지 않는다.'

new('05 / 요청 흐름', '가입 서비스의 실제 실행 순서', '가입 성공 결과 조회가 증명 사용 여부 검사보다 먼저 수행됩니다.', [java('account-lifecycle','application','SignupService')])
table(['단계','실행 내용','주요 담당'],[
    ['트랜잭션 전','입력 확인·scope/지문·비밀번호 해시','SignupService · identity'],
    ['트랜잭션 시작','멱등 잠금·기존 성공 결과 복원/충돌 확인','DatabaseGuard · SignupRecords'],
    ['검증 / 회원','proof 검증 → 회원 생성','identity → member'],
    ['동의 / 계정','약관 검사·저장 → 계정 생성·proof 소비','terms → identity'],
    ['보상 / 인증','가입 5크레딧 → 세션·토큰 생성','credit → identity'],
    ['결과 확정','성공 응답 암호화·멱등 기록 저장·커밋','SignupRecords'],
], [2.15,6.25,3.85], row_h=.59, font=14)

compare('트랜잭션은 모듈을 넘어 하나로 묶인다',
    '@Transactional SignupService\n여러 Repository 변경을 함께 커밋\n중간 실패 시 전체 롤백\n\n같은 앱·DB에서 구현 가능',
    'SignupService의 TransactionTemplate\nREAD_COMMITTED 격리 수준\nmember·terms·identity·credit 변경을 묶음\n\nterms와 credit의 @Transactional도\n기존 가입 트랜잭션에 참여',
    'Gradle 모듈 경계와 DB 트랜잭션 경계는 다르다. 같은 트랜잭션 구성이 전제다.',
    [java('account-lifecycle','application','SignupService'),java('terms','application','TermService'),java('credit','application','SignupCreditService')],chapter='05 / 요청 흐름')

new('05 / 요청 흐름', '재시도와 동시성: 이름이 비슷해도 목적은 다르다', '회원과 보상이 중복 생성되지 않도록 여러 장치를 함께 사용합니다.', ['modules/support/src/main/java/kr/nospo/support/DatabaseGuard.java',java('account-lifecycle','application','SignupService'),java('account-lifecycle','adapter-persistence','JdbcSignupRecords')])
table(['장치','해결하는 문제','현재 구현'],[
    ['멱등 기록','성공 응답 유실 후 같은 요청 재전송','proof 기반 scope + UUID 키 + 요청 지문'],
    ['DB 잠금','동시에 같은 작업을 처리하는 경합','auth_guard 행 upsert 잠금'],
    ['유일 제약 / 원자 갱신','경합으로 중복 데이터가 저장되는 상황','닉네임·이메일 충돌 / proof 소비 검사'],
    ['요청 제한','인증 메일 요청 과다','이메일 5회/시간 · IP 30회/시간'],
    ['발송 임대 / 소유 확인','죽은 워커 작업 복구와 중복 처리','claim · lease token · finish'],
], [2.3,4.7,5.25], row_h=.66, font=14)
takeaway('멱등 결과는 24시간 보관. 요청 제한 카운터는 별도의 REQUIRES_NEW 트랜잭션으로 처리한다.')

new('05 / 요청 흐름', '인증된 /me: JWT 외에 DB 세션도 검사', '현재 구현은 JWT 서명만 확인하고 끝나는 방식이 아닙니다.', ['src/main/java/kr/nospo/nospo_backend/config/SecurityConfig.java','src/main/java/kr/nospo/nospo_backend/config/AuthConfiguration.java',java('identity','application','SignupIdentityService')])
flow(['Security 필터','JWT 검증','추가 상태 검사','/me 응답'], ['Bearer 추출\n인증 필요 경로 확인', 'HS256 서명·만료·issuer\nsubject=회원 UUID', 'sid 세션 활성 여부\nmember 활성 여부', '회원 프로필 + 이메일\n인증 주체로 ID 확인'], 'Refresh 쿠키는 14일로 발급되지만, 현재 Refresh 재발급 API는 아직 없다.')

new('06 / 데이터와 설정', '실제 스키마는 migration을 기준으로 읽기', 'DB는 공유하지만 테이블을 수정할 책임은 각 업무가 갖습니다.', ['src/main/resources/db/migration/V1__email_signup.sql','erd/NOSPO_Auth.erd.json'])
table(['소유 책임','현재 테이블'],[
    ['member','member'],
    ['identity: 계정·세션','account · password_credential · auth_session · refresh_token'],
    ['identity: 인증·발송','auth_challenge · auth_proof · email_delivery'],
    ['terms','term · term_version · member_consent'],
    ['credit','credit_wallet · credit_transaction'],
    ['lifecycle / 기술 지원','idempotency_record / auth_guard · auth_rate_limit'],
], [3.35,8.9], row_h=.59, font=15)
takeaway('V1 마이그레이션은 16개 테이블. ERD의 카카오·탈퇴 설계와 현재 스키마는 일치 범위가 다르다.')

new('06 / 데이터와 설정', 'resources와 실행 설정의 역할', '현재 애플리케이션은 .env를 자동으로 읽지 않으며, 실행 환경변수로 설정합니다.', ['src/main/resources/application.properties','src/test/resources/application-test.properties','README.md'])
table(['설정 / 파일','현재 의미'],[
    ['application.properties','DB·메일·인증·Swagger 설정, 실제 값은 환경변수로 주입'],
    ['db/migration/V1__email_signup.sql','Flyway 스키마 생성, JPA ddl-auto=none'],
    ['AUTH_ENCRYPTION_KEY / HMAC_KEY / JWT_KEY','서로 다른 Base64 32바이트 키, 미설정 시 시작 실패'],
    ['DB_* / SMTP_* / MAIL_FROM','MySQL 연결 / SMTP 제공자 설정'],
    ['AUTH_ALLOWED_ORIGINS / SECURE_COOKIE','허용 Origin과 Refresh 쿠키의 보안 속성'],
    ['application-test.properties','H2 MySQL 모드, 테스트 전용 키, 워커 비활성화'],
], [5.55,6.7], row_h=.59, font=14)

new('06 / 데이터와 설정', '테스트 디렉터리는 시스템 동작을 설명하는 예제', '현재 주요 검증 코드는 각 하위 모듈이 아니라 루트 src/test에 모여 있습니다.', ['src/test/java/kr/nospo/nospo_backend','src/test/resources/application-test.properties'])
table(['파일','역할'],[
    ['EmailSignupTests','가입 원자성·멱등성·경합·재발송·메일 재시도·HTTP 계약'],
    ['MySqlEmailSignupTests','위 가입 테스트를 MySQL 8.4.7 컨테이너에서 실행'],
    ['SwaggerUiTests / SmtpEmailSenderTests','문서 접근·인증 / SMTP 영구·일시 오류 분류'],
    ['NospoBackendApplicationTests','테스트 프로필로 Spring 컨텍스트 로딩'],
    ['TestcontainersConfiguration','개발용 컨테이너 Bean, 이미지 mysql:latest'],
    ['TestNospoBackendApplication','컨테이너 설정을 붙여 앱 실행하는 테스트 진입점'],
], [4.85,7.4], row_h=.59, font=14)
takeaway('H2 빠른 테스트와 MySQL 검증은 목적이 다르다. Docker가 없으면 MySQL 테스트는 건너뛴다.')

new('06 / 데이터와 설정', '문서·ERD는 미래 범위까지 포함한다', '설계에 등장하는 기능을 모두 현재 구현으로 해석하면 구조가 더 혼란스러워집니다.', ['docs/NOSPO_Function_API_v2.1.md','docs/NOSPO_Development_Conventions.md','docs/NOSPO_API_Summary.xlsx','erd/NOSPO_Auth.erd.json','README.md'])
card(.53, 2.2, 5.96, 3.45, '현재 코드로 확인되는 범위', '이메일 인증과 비동기 메일 작업\n이메일 회원가입과 가입 보상\n약관 조회·동의 저장\nJWT·Refresh 발급, 내 정보 조회\n\n승인된 약관 본문은 별도 등록 필요', size=18)
card(6.77, 2.2, 6, 3.45, '설계 또는 후속 구현 범위', '기존 회원 로그인·카카오·탈퇴\nRefresh 회전·로그아웃·비밀번호 재설정\n도서·독서·위키·AI·공지 업무\n\nERD에는 social / oauth / withdrawal 포함\nAPI 요약 XLSX도 전체 명세 참고 자료', ORANGE, 18)
takeaway('현재 동작: Controller·서비스·migration / 목표 정책: 기능명세·개발 규칙·ERD를 대조')

new('07 / 개발 관점 비교', '멀티모듈로 얻는 것과 추가되는 비용', '단일 모듈도 잘 설계할 수 있고, 모듈만 나눈다고 좋은 구조가 보장되지는 않습니다.', ['settings.gradle','build.gradle','docs/NOSPO_Development_Conventions.md'])
table(['관점','단일 모듈','현재 멀티모듈'],[
    ['시작·탐색','파일 이동이 적고 초기에 단순','작은 기능도 여러 경로를 따라감'],
    ['경계','패키지·리뷰 규칙 중심','Gradle 의존성 + 공개 인터페이스'],
    ['변경 영향','내부 구현 직접 참조가 쉬움','계약을 유지하면 구현 변경을 줄일 수 있음'],
    ['협업','작은 팀·좁은 범위에서 편리','업무별 소유권이 보이지만 계약 협의 필요'],
    ['테스트·실행','한 프로젝트에서 쉽게 통합','분리 테스트 가능, 현재는 루트 통합 중심'],
    ['운영','하나의 서버로 배포 가능','현재도 하나의 서버, 자동 분산·고가용성 아님'],
], [1.8,5.0,5.45], row_h=.59, font=14)

new('07 / 개발 관점 비교', '수정 요청이 오면 어느 디렉터리로 갈까?', '단일 모듈에서 찾던 위치를 현재 구조에 대응해 봅니다.', ['modules','src/main/java/kr/nospo/nospo_backend/config'])
table(['변경 요구','단일 모듈의 흔한 위치','현재 시작 위치'],[
    ['가입 JSON / Refresh 쿠키','SignupController · dto/','lifecycle/adapter-web/SignupController'],
    ['닉네임 허용 문자','MemberService · validator/','member/domain/MemberInput'],
    ['가입 보상 수량','CreditService','credit/application/SignupCreditService'],
    ['가입 단계·원자성','SignupService','lifecycle/application/SignupService'],
    ['메일 재시도 / SMTP 발송','MailService','identity/application / adapter-integration'],
    ['DB 컬럼 추가','Entity · Repository · SQL','migration + 소유 업무의 persistence'],
], [2.65,4.05,5.55], row_h=.59, font=13)

new('07 / 개발 관점 비교', '예제: 닉네임 규칙 변경의 영향 경로', '실제 구현을 바꾸지 않고, 변경 시 검토할 위치를 따라가는 학습 예제입니다.', [java('member','domain','MemberInput'),java('member','application','MemberService'),java('account-lifecycle','adapter-web','SignupController'),'src/main/resources/db/migration/V1__email_signup.sql'])
flow(['업무 규칙','입력·저장 확인','호출자 확인','검증·문서'], ['MemberInput.nickname\n길이·허용 문자 변경', 'Controller 제한\nDB 컬럼·유일 키 확인', 'Members 계약 유지 시\n가입 호출부 변경 최소화', '중복·정규화 회귀 검증\nAPI와 설명 갱신'], '변경 위치는 줄어들 수 있지만, HTTP 검증과 DB 제약의 일관성은 함께 확인해야 한다.')

new('08 / 학습 순서', '추천 코드 읽기 순서: 작은 경로부터 전체로', '한 번에 모든 모듈을 외우기보다, 한 호출이 지나가는 파일을 연결하세요.', ['docs/NOSPO_Multi_Module_Study_Guide.md','src/test/java/kr/nospo/nospo_backend/EmailSignupTests.java'])
table(['순서','읽을 코드','답해 볼 질문'],[
    ['1','settings.gradle → root build.gradle','어떤 모듈이 실행 앱에 포함되는가?'],
    ['2','Members → MemberService → Store → JdbcMembers','인터페이스마다 누가 호출하고 누가 구현하는가?'],
    ['3','SignupController → SignupService','가입의 순서와 트랜잭션은 누가 책임지는가?'],
    ['4','EmailVerificationService → EmailWorker / Delivery','요청 접수와 실제 전송은 왜 분리됐는가?'],
    ['5','SecurityConfig → AuthConfiguration','JWT 이외에 어떤 상태를 검사하는가?'],
    ['6','migration → Jdbc* → EmailSignupTests','테이블 소유권과 실패·경합 동작이 맞는가?'],
], [1.0,5.55,5.7], row_h=.59, font=14)

new('08 / 학습 확인', '이 다섯 질문에 답하면 구조를 이해한 것', '발표자 노트에는 짧은 해설을 넣었습니다.', ['docs/NOSPO_Multi_Module_Study_Guide.md'],
    '1. Members는 다른 업무가 사용하는 공개 기능이며 MemberStore는 회원 application이 요구하는 저장 포트다. 2. Gradle은 컴파일 의존성, Spring은 객체 생성과 주입을 담당한다. 3. 같은 앱·DB·트랜잭션 관리 아래 호출되므로 모듈을 넘어 함께 커밋 가능하다. 4. 워커는 현재 같은 앱의 스케줄러다. 5. Controller와 서비스 구현, migration을 명세와 대조해야 한다.')
for i, q in enumerate(['Members와 MemberStore는 왜 따로 필요한가?', 'Gradle 의존성과 Spring 생성자 주입의 차이는 무엇인가?', '모듈이 달라도 가입 전체를 롤백할 수 있는 이유는?', 'adapter-worker는 별도 서버라는 뜻인가?', '명세서의 API가 현재 구현됐는지는 어디서 확인하는가?']):
    rect(.55, 2.2+i*.73, .44, .44, TEAL, radius=True)
    text(.68, 2.27+i*.73, .25, .26, str(i+1), 12, WHITE)
    text(1.16, 2.24+i*.73, 11.3, .48, q, 19)

# Appendix: every production/test Java file is explicitly described.
roles = {
 'NospoBackendApplication':'Spring Boot 시작, kr.nospo 전체 컴포넌트 검색',
 'AuthConfiguration':'Clock, JWT encoder/decoder, 세션 검증, 스케줄 활성화',
 'SecurityConfig':'허용 경로, CORS·Origin 검사, Bearer 인증과 보안 오류',
 'OpenApiConfig':'Swagger 제목과 /me의 Bearer 인증 문서화',
 'SignupCleanup':'만료 멱등 결과 정리, 기본 60초 fixed delay',
 'Members':'회원 업무의 공개 인터페이스와 Profile record',
 'MemberService':'회원 입력 검증·정규화 후 저장 포트 호출',
 'MemberStore':'회원 생성·조회·닉네임·상태 확인 저장 포트',
 'MemberInput':'닉네임·전화번호 정규화와 검증 규칙',
 'JdbcMembers':'member 테이블 SQL, 중복 닉네임 오류 변환',
 'SignupIdentity':'가입 인증·계정·세션의 공개 인터페이스',
 'EmailAddress':'이메일 소문자화·공백 제거·형식 검증',
 'EmailVerificationService':'인증 발급·재발송·링크 확인·가입 proof 생성',
 'SignupIdentityService':'증명 검사·계정 생성·세션 발급·상태 조회',
 'EmailDeliveryService':'메일 작업 점유·전송·재시도·완료·정리',
 'IdentityStore':'인증·계정·세션·발송 작업 저장 포트와 record',
 'IdentityCrypto':'비밀번호 해시·Access Token 생성 포트',
 'EmailSender':'메일 전송 포트와 영구/일시 실패 표현',
 'JdbcIdentityStore':'identity 테이블 SQL, 작업 lease와 조건부 갱신',
 'SmtpEmailSender':'SMTP 발송, fragment 링크, SMTP 오류 분류',
 'JwtIdentityCrypto':'Argon2 비밀번호 해시, HS256 JWT 생성',
 'EmailVerificationController':'이메일 가용성·인증 요청·확인 HTTP API',
 'EmailWorker':'메일 처리·정리를 주기 실행하는 스케줄 입구',
 'Terms':'약관 조회·가입 동의 기록의 공개 계약',
 'TermService':'현행 약관·필수 동의 검증, 동의 저장 조율',
 'TermStore':'약관 조회·잠금·동의 저장 포트',
 'JdbcTerms':'현행 시행 버전 조회, term 잠금, 동의 INSERT',
 'TermsController':'현행 약관 목록·버전 상세 HTTP API',
 'SignupCredits':'가입 보상 지급 공개 인터페이스',
 'SignupCreditService':'가입 보상 수량 5 결정, 지급 호출',
 'CreditStore':'가입 지갑 생성과 보상 기록 저장 포트',
 'JdbcCredits':'credit_wallet와 SIGNUP 거래 원장 INSERT',
 'SignupController':'닉네임 가용성·가입·내 정보, Refresh 쿠키',
 'SignupService':'전체 가입 순서·트랜잭션·멱등 처리·내 정보 조합',
 'SignupResult':'가입 응답 및 회원 정보 record',
 'SignupRecords':'성공 멱등 결과 조회·저장·정리 포트',
 'JdbcSignupRecords':'idempotency_record SQL과 24시간 보관',
 'ApiResponse':'공통 성공 응답 data와 meta.requestId',
 'ApiException':'HTTP 상태·오류 코드·재시도 대기시간 표현',
 'ApiErrors':'예외→공통 HTTP 오류 응답, 민감 상세 노출 제한',
 'Secrets':'난수 token, SHA-256, HMAC 지문, AES-GCM 암호화',
 'SqlIds':'UUID↔바이트와 Instant→SQL Timestamp 변환',
 'DatabaseGuard':'DB 행 잠금, 독립 트랜잭션 요청 제한, TTL 정리',
 'EmailSignupTests':'가입·메일·동시성·재시도·HTTP 통합 검증',
 'MySqlEmailSignupTests':'동일 가입 테스트를 MySQL 8.4.7에서 실행',
 'SwaggerUiTests':'Swagger 공개 접근과 그 외 경로 인증 확인',
 'SmtpEmailSenderTests':'SMTP 5xx 영구 실패·4xx 재시도 분류 확인',
 'NospoBackendApplicationTests':'Spring 애플리케이션 컨텍스트 로딩',
 'TestcontainersConfiguration':'개발용 mysql:latest 컨테이너 Bean 설정',
 'TestNospoBackendApplication':'개발용 Testcontainers 구성으로 앱 실행',
}
groups = [
 ('루트 실행·설정', sorted((ROOT/'src/main/java').rglob('*.java'))),
 ('member', sorted((ROOT/'modules/member').rglob('*.java'))),
 ('identity / contract·domain·application', sorted(p for p in (ROOT/'modules/identity').rglob('*.java') if '/adapter-' not in str(p))),
 ('identity / adapters', sorted(p for p in (ROOT/'modules/identity').rglob('*.java') if '/adapter-' in str(p))),
 ('terms · credit', sorted(list((ROOT/'modules/terms').rglob('*.java'))+list((ROOT/'modules/credit').rglob('*.java')))),
 ('account-lifecycle · support', sorted(list((ROOT/'modules/account-lifecycle').rglob('*.java'))+list((ROOT/'modules/support').rglob('*.java')))),
 ('테스트', sorted((ROOT/'src/test/java').rglob('*.java'))),
]
inventory = []
for group, paths in groups:
    for start in range(0,len(paths),6):
        chunk = paths[start:start+6]
        new('APPENDIX / 전체 Java 파일 역할표', group + (f' ({start//6+1})' if len(paths)>6 else ''),
            '아래 경로는 소스 루트 이후의 업무 / 역할 / 파일명을 압축해 표시했습니다. 전체 경로는 발표자 노트에 있습니다.',
            [str(p.relative_to(ROOT)) for p in chunk])
        rows=[]
        for p in chunk:
            name=p.stem
            if name not in roles:
                raise ValueError(f'Missing role {name}')
            rel=p.relative_to(ROOT).as_posix()
            if rel.startswith('modules/'):
                bits=rel.split('/')
                location=f'{bits[1]} / {bits[2]}' if bits[1] != 'support' else 'support'
            else:
                location='root / '+('test' if '/test/' in rel else 'main')
            rows.append([name+'.java', location, roles[name]])
            inventory.append({'path':rel,'role':roles[name]})
        table(['파일','소속','역할'],rows,[4.05,3.05,5.15],row_h=.63,font=13)

new('APPENDIX / 전체 Gradle 모듈', '코드가 있는 하위 프로젝트 22개', '상위 업무 프로젝트는 묶음 역할이며, 필요한 역할만 실제 하위 모듈로 구현했습니다.', ['settings.gradle','build.gradle'])
table(['업무','실제 하위 Gradle 모듈','개수'],[
    ['member','contract · domain · application · adapter-persistence','4'],
    ['identity','contract · domain · application · adapter-persistence\nadapter-web · adapter-integration · adapter-worker','7'],
    ['terms','contract · application · adapter-persistence · adapter-web','4'],
    ['credit','contract · application · adapter-persistence','3'],
    ['account-lifecycle','application · adapter-persistence · adapter-web','3'],
    ['support','업무별 하위 역할 없이 단일 기술 지원 라이브러리','1'],
], [2.5,8.9,.85], row_h=.64, font=14)

new('APPENDIX / 나머지 관리 파일', '소스 외 파일도 역할을 연결해서 읽기', '자동 생성물과 IDE 내부 자료는 업무 설계 근거로 사용하지 않습니다.', ['README.md','HELP.md','.gitignore','gradle/wrapper/gradle-wrapper.properties','docs','erd'])
table(['파일 / 디렉터리','역할과 확인 포인트'],[
    ['README.md / HELP.md','프로젝트 실행·환경변수·검증 / 생성된 참고 문서'],
    ['gradlew · gradlew.bat · gradle/wrapper/*','Unix·Windows 빌드 진입점, Gradle 배포·Wrapper JAR'],
    ['settings.gradle / 각 build.gradle','프로젝트 등록·경로 매핑 / 의존성·플러그인·공통 설정'],
    ['docs/개발 규칙 · 기능/API 명세 · XLSX','업무 경계·정책·목표 API / 전체 API 요약 시트'],
    ['docs/학습 가이드 · presentation/','상세 코드 해설 / 이 PPT와 재생성 스크립트'],
    ['erd/NOSPO_Auth.erd.json','17개 설계 테이블, 현재 migration의 16개와 범위 차이'],
    ['.gitignore / .git · .idea · .gradle · build','추적 제외 설정 / 이력·IDE·캐시·컴파일·테스트 결과'],
], [5.4,6.85], row_h=.55, font=13)

new('APPENDIX / 실행과 출처', '실행 명령과 자료의 범위', '본 PPT 작성 과정에서는 애플리케이션 테스트를 실행하지 않았습니다.', ['README.md','settings.gradle','build.gradle','src/main/resources/db/migration/V1__email_signup.sql'])
card(.53, 2.2, 6.0, 3.46, '로컬에서 직접 확인하기', './gradlew bootRun\n→ Java 21·DB·키·SMTP 설정 필요\n\n./gradlew :test -PskipMysql\n→ H2 중심 테스트 (MySQL 제외)\n\n./gradlew :test --tests "*MySqlEmailSignupTests"\n→ Docker와 MySQL 통합 검증', size=16)
card(6.8, 2.2, 5.97, 3.46, '자료를 읽는 기준', '현재 소스와 설정을 우선 확인\nAPI·ERD의 후속 설계와 구분\n단일 모듈 예시는 비교용 가정\n각 슬라이드 발표자 노트에 근거 경로\n\nPPT 본문·표·도식은 편집 가능한 요소\nPNG는 동일 배치 명령으로 만든 미리보기', size=16)
takeaway('읽기 순서: 빌드 설정 → member 한 경로 → 가입 조합 → 인증·발송 → DB·테스트')

# Notes, coverage, artifact export.
all_java = sorted(list((ROOT/'src').rglob('*.java')) + list((ROOT/'modules').rglob('*.java')))
all_java = [p for p in all_java if 'build' not in p.relative_to(ROOT).parts]
assert {x['path'] for x in inventory} == {p.relative_to(ROOT).as_posix() for p in all_java}
assert len(list((ROOT/'modules').rglob('build.gradle'))) == 22
assert len(all_java) == 50
for d in slides_data:
    d['slide'].notes_slide.notes_text_frame.text += '\n'.join(d['content'])
    preview_pages.append(d['image'])

pptx_path = OUT/'NOSPO_Project_Architecture_Study.pptx'
prs.save(pptx_path)
outline = ['# NOSPO 프로젝트 구조와 단일 모듈 비교 — 슬라이드 원고',
           '', '기준: 2026-11-08 작업 디렉터리. 단일 모듈 예시는 일반적인 비교용 구성.',
           'PPT 본문은 편집 가능하며 전체 Java 파일 50개를 부록에서 설명한다.', '']
for i,d in enumerate(slides_data,1):
    outline += [f'## {i:02}. {d["content"][1]}','']
    outline += [s+'\n' for s in d['content'][2:] if not s.startswith('NOSPO  /')]
    outline += ['근거: '+', '.join(f'`{s}`' for s in d['sources']), '']
(OUT/'NOSPO_Project_Architecture_Slide_Notes.md').write_text('\n'.join(outline))
(OUT/'source_inventory.json').write_text(json.dumps(inventory,ensure_ascii=False,indent=2))

# Contact sheets at readable thumbnail size; layout previews, not PPT renders.
for batch_start in range(0,len(preview_pages),12):
    chunk=preview_pages[batch_start:batch_start+12]
    cols=3
    rows=math.ceil(len(chunk)/cols)
    thumb_w,thumb_h=640,360
    sheet=Image.new('RGB',(cols*thumb_w,rows*(thumb_h+30)), '#DDE5EA')
    draw=ImageDraw.Draw(sheet)
    f=ImageFont.truetype(FONT_PATH,18)
    for j,img in enumerate(chunk):
        x,y=(j%cols)*thumb_w,(j//cols)*(thumb_h+30)
        draw.text((x+12,y+5),f'SLIDE {batch_start+j+1:02} · LAYOUT PREVIEW',font=f,fill=NAVY)
        sheet.paste(img.resize((thumb_w,thumb_h),Image.Resampling.LANCZOS),(x,y+30))
    sheet.save(OUT/f'preview_{batch_start//12+1:02}.png')

reopened=Presentation(pptx_path)
assert len(reopened.slides)==len(slides_data)
assert all(len(s.notes_slide.notes_text_frame.text)>40 for s in reopened.slides)
(OUT/'validation.json').write_text(json.dumps({
    'slide_count':len(reopened.slides),'java_files_documented':len(inventory),
    'gradle_code_submodules':22,'source_paths_checked':True,
    'text_boxes_checked':len(checks),'notes_on_all_slides':True,
    'preview_method':'Pillow drawing of shared layout instructions; not a PowerPoint render',
    'application_tests_run':False,
},ensure_ascii=False,indent=2))
print(json.dumps({'pptx':str(pptx_path),'slides':len(reopened.slides),'java_files':len(inventory),'text_boxes':len(checks)},ensure_ascii=False))
