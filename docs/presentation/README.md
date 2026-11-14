# NOSPO 프로젝트 구조 학습 PPT

- [편집 가능한 PPTX](NOSPO_Project_Architecture_Study.pptx): 49장. 1~35장은 구조·비교·흐름·학습, 36~49장은 전체 Java 파일 역할표·모듈 목록·실행 참고 부록.
- [슬라이드 원고](NOSPO_Project_Architecture_Slide_Notes.md): 본문을 검색하거나 텍스트로 복습하는 자료.
- `source_inventory.json`: Java 파일 50개의 경로와 역할.
- `preview_01.png` ~ `preview_05.png`: 같은 배치 명령으로 생성한 미리보기. PowerPoint 렌더링 화면은 아님.
- `validation.json`: 파일 재열기, 슬라이드 수, 소스 경로, Java 파일 전체 포함, 텍스트 배치 검사 결과.

현재 저장소의 실행 코드와 설정을 기준으로 작성했다. 단일 모듈 예시는 비교를 위한 일반적인 구성이며 과거 버전의 실제 코드를 복원한 것이 아니다. 목표 명세·ERD와 현재 구현 범위를 구분했다. `.git`, IDE 설정, 캐시, 빌드 산출물은 역할만 설명하고 내부 생성물을 업무 코드로 다루지 않았다.

모든 슬라이드의 발표자 노트에 근거 파일·디렉터리를 기록했다. PPT의 본문·표·도식은 편집 가능한 도형과 텍스트로 구성되어 있다. PowerPoint에서 여는 환경에 `Apple SD Gothic Neo`가 없으면 한국어 글꼴로 대체될 수 있다.

재생성하려면 `python-pptx`와 `Pillow`, 한국어 글꼴이 필요하다. 스크립트의 `FONT_PATH`는 macOS의 Apple SD Gothic Neo 경로를 사용한다. 다른 환경에서는 해당 경로와 `FONT`를 설치된 한국어 글꼴로 변경한다.

```bash
python docs/presentation/build_deck.py
```

본문 변경은 `build_deck.py`에서 수정 후 재생성한다. 생성 스크립트는 소스 경로 존재 여부와 전체 Java 파일 포함 여부를 검사한다. 애플리케이션 테스트는 이 문서 작업에서 실행하지 않았다.
