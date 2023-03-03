package it.pagopa.pn.ec.pec.utils;

import it.pagopa.pn.ec.commons.model.pojo.PresaInCaricoInfo;
import it.pagopa.pn.ec.pec.exception.MessageIdException;
import org.springframework.util.Base64Utils;

import static java.nio.charset.StandardCharsets.UTF_8;

public class MessageIdUtils {

    private static final String SEPARATORE = "~";
    private static final String DOMAIN = "@pagopa.it";

    private MessageIdUtils() {
        throw new IllegalStateException("MessageIdUtils is a utility class");
    }

    public static String encodeMessageId(String idRequest, String idClient) {
        try {
            return String.format("%s%s%s%s",
                                 Base64Utils.encodeToString(idRequest.getBytes()),
                                 SEPARATORE,
                                 Base64Utils.encodeToString(idClient.getBytes()),
                                 DOMAIN);
        } catch (Exception e) {
            throw new MessageIdException.EncodeMessageIdException();
        }
    }

    /**
     * @param messageId base64RequestId~base64ClientId@pagopa.it
     * @return The decoded requestId
     */
    public static PresaInCaricoInfo decodeMessageId(String messageId) {
        try {
            var splitAtPipe = messageId.split(SEPARATORE);
            var base64RequestId = splitAtPipe[0];
            var base64ClientId = splitAtPipe[1].split(String.valueOf(DOMAIN.charAt(0)))[0];
            return new PresaInCaricoInfo(new String(Base64Utils.decodeFromString(base64RequestId), UTF_8),
                                         new String(Base64Utils.decodeFromString(base64ClientId), UTF_8));
        } catch (Exception e) {
            throw new MessageIdException.DecodeMessageIdException();
        }
    }
}
