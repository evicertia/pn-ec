package it.pagopa.pn.ec.pec;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class getPecMessagesTest {

    ServicePecImpl service = new ServicePecImpl();

    @Test
    void getMessagesPecPositive() {
        ParametriRicercaPec parametri = new ParametriRicercaPec();
        parametri.setEmail("test@test.com");
        Assertions.assertTrue(service.getPecMessages(parametri), " caso positivo");
    }

    @Test
    void getMessagesPecError1() {
        ParametriRicercaPec parametri = new ParametriRicercaPec();
        parametri.setEmail("test?test.com");
        Assertions.assertFalse(service.getPecMessages(parametri), "Sintassi malformed");
    }

    @Test
    void getMessagesPecError2() {
        ParametriRicercaPec parametri = new ParametriRicercaPec();
        service.getPecMessages(parametri);
        Assertions.assertFalse(service.getPecMessages(parametri), " Null pointer exception ");
    }


}
