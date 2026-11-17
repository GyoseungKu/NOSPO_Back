package kr.nospo.nospo_backend;

import static org.assertj.core.api.Assertions.*;
import kr.nospo.support.ApiException;
import kr.nospo.terms.application.*;
import kr.nospo.terms.contract.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

class ReconsentPolicyTests {
    final UUID service=UUID.randomUUID(),optional=UUID.randomUUID();
    final Instant start=Instant.parse("2026-11-17T00:00:00Z");
    Terms.Version version(UUID term,int number,boolean required,boolean reconsent) {
        return new Terms.Version(UUID.randomUUID(),term,term.equals(optional)?"OPTIONAL":"SERVICE",
                Integer.toString(number),"fixture",required,start.plusSeconds(number),reconsent);
    }
    ConsentStore.Ordered choice(Terms.Version version,boolean agreed,long order) {
        return new ConsentStore.Ordered(new MemberConsents.Event(UUID.randomUUID(),version.termVersionId(),agreed,
                start.minusSeconds(order)),order);
    }
    ReconsentPolicy.State state(List<Terms.Version> versions,ConsentStore.Ordered... events) {
        return ReconsentPolicy.evaluate(versions,List.of(events));
    }
    @Test void currentValidConsentHasNoPendingObligation() {
        var v=version(service,1,true,true);
        assertThat(state(List.of(v),choice(v,true,1)).pending()).isEmpty();
    }
    @Test void requiredRevisionCreatesOneCurrentTarget() {
        var one=version(service,1,true,false);var two=version(service,2,true,true);
        assertThat(state(List.of(one,two),choice(one,true,1)).pending()).containsExactly(two);
    }
    @Test void missingInitialRequiredConsentTargetsOnlyTheLatestVersion() {
        var one=version(service,1,true,false);var two=version(service,2,true,false);
        assertThat(state(List.of(one,two)).pending()).containsExactly(two);
    }
    @Test void unpaidRequiredObligationSurvivesLatestFlagFalse() {
        var one=version(service,1,true,false);var two=version(service,2,true,true);var three=version(service,3,true,false);
        assertThat(state(List.of(three,one,two),choice(one,true,1)).pending()).containsExactly(three);
    }
    @Test void fulfilledPreviousObligationDoesNotRequireUnflaggedNewRevision() {
        var one=version(service,1,true,true);var two=version(service,2,true,false);
        assertThat(state(List.of(one,two),choice(one,true,1)).pending()).isEmpty();
    }
    @Test void severalUnpaidRevisionsRequireOnlyLatestVersion() {
        var one=version(service,1,true,true);var two=version(service,2,true,true);var three=version(service,3,true,false);
        assertThat(state(List.of(one,two,three)).pending()).containsExactly(three);
    }
    @Test void latestConsentFromSignupOrRecoveryCoversAllOlderObligations() {
        var one=version(service,1,true,true);var two=version(service,2,true,true);var three=version(service,3,true,false);
        assertThat(state(List.of(one,two,three),choice(three,true,8)).pending()).isEmpty();
    }
    @Test void optionalExplicitFalseIsACompletedChoice() {
        var v=version(optional,1,false,true);
        assertThat(state(List.of(v),choice(v,false,1)).pending()).isEmpty();
    }
    @Test void optionalFlaggedRevisionIsPendingUntilAChoiceIsRecorded() {
        var one=version(optional,1,false,false);var two=version(optional,2,false,true);
        assertThat(state(List.of(one,two),choice(one,true,1)).pending()).containsExactly(two);
        assertThat(state(List.of(one,two),choice(one,true,1),choice(two,false,2)).pending()).isEmpty();
    }
    @Test void optionalUnflaggedRevisionDoesNotCreateOrInheritAnObligation() {
        var one=version(optional,1,false,true);var two=version(optional,2,false,false);
        assertThat(state(List.of(one,two)).pending()).isEmpty();
    }
    @Test void internalOrderOverridesTimestampAndInputListOrder() {
        var v=version(optional,1,false,true);
        var old=choice(v,true,1);var recovery=choice(v,false,9);
        var result=state(List.of(v),old,recovery);
        assertThat(result.choices().get(v.termVersionId())).isEqualTo(recovery.event());
        assertThat(result.pending()).isEmpty();
        assertThat(recovery.event().agreedAt()).isBefore(old.event().agreedAt());
    }
    @Test void overwrittenTrueIsNotUsedAsRequiredCoverage() {
        var one=version(service,1,true,true);var two=version(service,2,true,false);
        assertThat(state(List.of(one,two),choice(one,true,1),choice(one,false,2)).pending()).containsExactly(two);
    }
    @Test void requirednessTransitionsWithoutCurrentDecisionAreDeferred() {
        for(boolean required:List.of(false,true)) {
            var one=version(service,1,!required,true);var two=version(service,2,required,true);
            assertThatThrownBy(()->state(List.of(one,two),choice(one,true,1)))
                    .isInstanceOfSatisfying(ApiException.class,e->{
                        assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo("TERMS_VERSION_CHANGED");
                    });
        }
    }
    @Test void completedLatestDecisionDoesNotResurrectTransitionHistory() {
        var one=version(service,1,false,true);var two=version(service,2,true,true);
        assertThat(state(List.of(one,two),choice(two,true,5)).pending()).isEmpty();
    }
    @Test void optionalFalseDoesNotSilentlyEraseDebtAcrossARequirednessTransition() {
        var one=version(service,1,true,true);var two=version(service,2,false,true);
        assertThatThrownBy(()->state(List.of(one,two),choice(two,false,5)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("TERMS_VERSION_CHANGED"));
    }
    @Test void independentKindsAreKeptSeparate() {
        var required=version(service,1,true,true);var choice=version(optional,1,false,true);
        var result=state(List.of(required,choice));
        assertThat(result.pending()).containsExactly(choice,required);
        assertThat(state(List.of(required,choice),choice(required,true,1)).pending()).containsExactly(choice);
    }
    @Test void undefinedOrInconsistentKindScopeIsDeferred() {
        for(var type:List.of("SERVICE","PRIVACY","OPTIONAL","UNSCOPED")) {
            boolean required=type.equals("OPTIONAL") || type.equals("UNSCOPED");
            var v=new Terms.Version(UUID.randomUUID(),UUID.randomUUID(),type,"1","fixture",required,start,true);
            assertThatThrownBy(()->state(List.of(v)))
                    .isInstanceOfSatisfying(ApiException.class,e->{
                        assertThat(e.status()).isEqualTo(409);assertThat(e.code()).isEqualTo("TERMS_VERSION_CHANGED");
                    });
        }
    }
}
