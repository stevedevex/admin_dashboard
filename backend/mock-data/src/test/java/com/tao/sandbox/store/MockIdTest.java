package com.tao.sandbox.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class MockIdTest {

    @Test
    void roundTripsThroughItsPathForm() {
        MockId id = MockId.parse("baseline/petstore/showPetById/petid=1.json");

        assertThat(id.scenarioId()).isEqualTo("baseline");
        assertThat(id.operationId()).isEqualTo("showPetById");
        assertThat(id.fileName()).isEqualTo("petid=1.json");
        assertThat(id.asPath()).isEqualTo("baseline/petstore/showPetById/petid=1.json");
    }

    /**
     * The control panel accepts an id straight from a URL, and the filesystem store turns one into
     * a path by resolution. Anything that escapes the mock root has to be refused here, because
     * below this point it is indistinguishable from a legitimate address.
     */
    @Test
    void refusesAPartThatIsAPathRatherThanASegment() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MockId.parse("baseline/petstore/showPetById/../../../etc/passwd"))
                .withMessageContaining("one directory or file");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockId("..", "petstore", "showPetById", "petid=1.json"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MockId("baseline", "petstore", "showPetById", "sub\\dir.json"));
    }

    @Test
    void refusesAnIdThatIsNotFourParts() {
        assertThatIllegalArgumentException().isThrownBy(() -> MockId.parse("baseline/petstore"));
    }

    @Test
    void refusesAnEmptyPart() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> MockId.parse("baseline//showPetById/petid=1.json"))
                .withMessageContaining("service");
    }
}
