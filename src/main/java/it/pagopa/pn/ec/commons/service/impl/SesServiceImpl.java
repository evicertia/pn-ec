package it.pagopa.pn.ec.commons.service.impl;

import static org.springframework.http.MediaType.APPLICATION_OCTET_STREAM_VALUE;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Message;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import org.springframework.stereotype.Service;

import it.pagopa.pn.ec.commons.exception.ses.SesSendException;
import it.pagopa.pn.ec.commons.model.pojo.email.EmailAttachment;
import it.pagopa.pn.ec.commons.model.pojo.email.EmailField;
import it.pagopa.pn.ec.commons.service.SesService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.ses.SesAsyncClient;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;
import software.amazon.awssdk.services.ses.model.SendRawEmailResponse;

@Service
@Slf4j
public class SesServiceImpl implements SesService {

    private final SesAsyncClient sesAsyncClient;

    public SesServiceImpl(SesAsyncClient sesAsyncClient) {
        this.sesAsyncClient = sesAsyncClient;
    }

    @Override
    public Mono<SendRawEmailResponse> send(EmailField field) {
        log.info("<-- START SENDING EMAIL  -->");
        return Mono.fromCallable(() -> composeSendRawEmailRequest(field))
                   .flatMap(sendRawEmailRequest -> Mono.fromCompletionStage(sesAsyncClient.sendRawEmail(sendRawEmailRequest)))
                   .onErrorResume(throwable -> {
                       log.error(throwable.getMessage());
                       return Mono.error(new SesSendException());
                   })
                   .doOnSuccess(sendMessageResponse -> log.debug("Send MAIL has returned a {} as status", sendMessageResponse.sdkHttpResponse().statusCode()));
    }

    private SendRawEmailRequest composeSendRawEmailRequest(EmailField field) throws IOException, MessagingException {

        Session session = Session.getInstance(new Properties(System.getProperties()));
        MimeMessage mimeMessage = new MimeMessage(session);

        mimeMessage.setFrom(new InternetAddress(field.getFrom(), "", "UTF-8"));
        mimeMessage.setRecipient(Message.RecipientType.TO, new InternetAddress(field.getTo(), "", "UTF-8"));
        mimeMessage.setSubject(field.getSubject(), "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent(field.getText(), field.getContentType());

        MimeMultipart msgBody = new MimeMultipart("alternative");
        msgBody.addBodyPart(htmlPart);

        MimeBodyPart wrap = new MimeBodyPart();
        wrap.setContent(msgBody);

        MimeMultipart msg = new MimeMultipart("mixed");
        msg.addBodyPart(wrap);

        // Add multiple files to attachment
        List<EmailAttachment> files = field.getEmailAttachments();
        for (EmailAttachment file : files) {
            MimeBodyPart messageBodyPart = new MimeBodyPart();

            var byteArrayOutputStream = (ByteArrayOutputStream) file.getContent();
            DataSource source = new ByteArrayDataSource(byteArrayOutputStream.toByteArray(), APPLICATION_OCTET_STREAM_VALUE);
            messageBodyPart.setDataHandler(new DataHandler(source));
            messageBodyPart.setFileName(file.getNameWithExtension());

            msg.addBodyPart(messageBodyPart);
        }

        mimeMessage.setContent(msg);

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        mimeMessage.writeTo(outputStream);

        SdkBytes data = SdkBytes.fromByteBuffer(ByteBuffer.wrap(outputStream.toByteArray()));

        return SendRawEmailRequest.builder().rawMessage(builder -> builder.data(data)).build();
    }

}
