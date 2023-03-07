package it.pagopa.pn.ec.pec.service.impl;

import io.awspring.cloud.messaging.listener.Acknowledgment;
import io.awspring.cloud.messaging.listener.SqsMessageDeletionPolicy;
import io.awspring.cloud.messaging.listener.annotation.SqsListener;
import it.pagopa.pn.ec.commons.configurationproperties.TransactionProcessConfigurationProperties;
import it.pagopa.pn.ec.commons.configurationproperties.sqs.NotificationTrackerSqsName;
import it.pagopa.pn.ec.commons.exception.EcInternalEndpointHttpException;
import it.pagopa.pn.ec.commons.exception.aruba.ArubaSendException;
import it.pagopa.pn.ec.commons.exception.sqs.SqsPublishException;
import it.pagopa.pn.ec.commons.model.dto.NotificationTrackerQueueDto;
import it.pagopa.pn.ec.commons.model.pojo.PresaInCaricoInfo;
import it.pagopa.pn.ec.commons.rest.call.aruba.ArubaCall;
import it.pagopa.pn.ec.commons.rest.call.ec.gestorerepository.GestoreRepositoryCall;
import it.pagopa.pn.ec.commons.service.AuthService;
import it.pagopa.pn.ec.commons.service.PresaInCaricoService;
import it.pagopa.pn.ec.commons.service.SqsService;
import it.pagopa.pn.ec.commons.service.impl.AttachmentServiceImpl;
import it.pagopa.pn.ec.pec.configurationproperties.PecSqsQueueName;
import it.pagopa.pn.ec.pec.model.pojo.PecPresaInCaricoInfo;
import it.pagopa.pn.ec.rest.v1.dto.*;
import it.pec.bridgews.SendMail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static it.pagopa.pn.ec.commons.rest.call.aruba.ArubaCall.DEFAULT_RETRY_STRATEGY;
import static it.pagopa.pn.ec.commons.utils.SqsUtils.logIncomingMessage;
import static it.pagopa.pn.ec.pec.utils.MessageIdUtils.encodeMessageId;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalNotificationRequest.QosEnum.BATCH;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalNotificationRequest.QosEnum.INTERACTIVE;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestMetadataDto.ChannelEnum.PEC;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestMetadataDto.MessageContentTypeEnum.PLAIN;
import static java.time.OffsetDateTime.now;

@Service
@Slf4j
public class PecService extends PresaInCaricoService {

    private final SqsService sqsService;
    private final ArubaCall arubaCall;
    private final GestoreRepositoryCall gestoreRepositoryCall;
    private final AttachmentServiceImpl attachmentService;
    private final NotificationTrackerSqsName notificationTrackerSqsName;
    private final PecSqsQueueName pecSqsQueueName;
    private final TransactionProcessConfigurationProperties transactionProcessConfigurationProperties;

    private static final String SEPARATORE = "~";


    protected PecService(AuthService authService, ArubaCall arubaCall, GestoreRepositoryCall gestoreRepositoryCall, SqsService sqsService,
                         AttachmentServiceImpl attachmentService, NotificationTrackerSqsName notificationTrackerSqsName,
                         PecSqsQueueName pecSqsQueueName,
                         TransactionProcessConfigurationProperties transactionProcessConfigurationProperties) {
        super(authService, gestoreRepositoryCall);
        this.arubaCall = arubaCall;
        this.sqsService = sqsService;
        this.gestoreRepositoryCall = gestoreRepositoryCall;
        this.attachmentService = attachmentService;
        this.notificationTrackerSqsName = notificationTrackerSqsName;
        this.pecSqsQueueName = pecSqsQueueName;
        this.transactionProcessConfigurationProperties = transactionProcessConfigurationProperties;
    }

    @Override
    protected Mono<Void> specificPresaInCarico(final PresaInCaricoInfo presaInCaricoInfo) {
//      Cast PresaInCaricoInfo to specific PecPresaInCaricoInfo
        var pecPresaInCaricoInfo = (PecPresaInCaricoInfo) presaInCaricoInfo;
        var xPagopaExtchCxId = presaInCaricoInfo.getXPagopaExtchCxId();
        return attachmentService.checkAllegatiPresence(pecPresaInCaricoInfo.getDigitalNotificationRequest().getAttachmentsUrls(),
                                                       xPagopaExtchCxId,
                                                       true)
                                .flatMap(fileDownloadResponse -> {
                                   var digitalNotificationRequest = pecPresaInCaricoInfo.getDigitalNotificationRequest();
                                   digitalNotificationRequest.setRequestId(presaInCaricoInfo.getRequestIdx());
//                                 Insert request from PEC request and publish to Notification Tracker with next status -> BOOKED
                                   return insertRequestFromPec(digitalNotificationRequest, xPagopaExtchCxId)
                                           .onErrorResume(throwable -> Mono.error(new EcInternalEndpointHttpException()));
                               })
                                .flatMap(requestDto -> sqsService.send(notificationTrackerSqsName.statoPecName(),
                                                                      new NotificationTrackerQueueDto(presaInCaricoInfo.getRequestIdx(),
                                                                                                      presaInCaricoInfo.getXPagopaExtchCxId(),
                                                                                                      now(),
                                                                                                      transactionProcessConfigurationProperties.pec(),
                                                                                                      transactionProcessConfigurationProperties.pecStartStatus(),
                                                                                                      "booked",
                                                                                                      // TODO: SET eventDetails
                                                                                                      "",
                                                                                                      null)))
//                              Publish to PEC INTERACTIVE or PEC BATCH
                                .flatMap(sendMessageResponse -> {
                                   DigitalNotificationRequest.QosEnum qos = pecPresaInCaricoInfo.getDigitalNotificationRequest().getQos();
                                   if (qos == INTERACTIVE) {
                                       return sqsService.send(pecSqsQueueName.interactiveName(),
                                                              pecPresaInCaricoInfo);
                                   } else if (qos == BATCH) {
                                       return sqsService.send(pecSqsQueueName.batchName(),
                                                              pecPresaInCaricoInfo);
                                   } else {
                                       return Mono.empty();
                                   }
                               })
                                .then();
    }

    @SuppressWarnings("Duplicates")
    private Mono<RequestDto> insertRequestFromPec(final DigitalNotificationRequest digitalNotificationRequest,
                                                  String xPagopaExtchCxId) {
        return Mono.fromCallable(() -> {
            var requestDto = new RequestDto();
            requestDto.setRequestIdx(digitalNotificationRequest.getRequestId());
            requestDto.setClientRequestTimeStamp(digitalNotificationRequest.getClientRequestTimeStamp());
            requestDto.setxPagopaExtchCxId(xPagopaExtchCxId);

            var requestPersonalDto = new RequestPersonalDto();
            var digitalRequestPersonalDto = new DigitalRequestPersonalDto();
            digitalRequestPersonalDto.setQos(DigitalRequestPersonalDto.QosEnum.valueOf(digitalNotificationRequest.getQos().name()));
            digitalRequestPersonalDto.setReceiverDigitalAddress(digitalNotificationRequest.getReceiverDigitalAddress());
            digitalRequestPersonalDto.setMessageText(digitalNotificationRequest.getMessageText());
            digitalRequestPersonalDto.setSenderDigitalAddress(digitalNotificationRequest.getSenderDigitalAddress());
            digitalRequestPersonalDto.setSubjectText(digitalNotificationRequest.getSubjectText());
            digitalRequestPersonalDto.setAttachmentsUrls(digitalNotificationRequest.getAttachmentsUrls());
            requestPersonalDto.setDigitalRequestPersonal(digitalRequestPersonalDto);

            var requestMetadataDto = new RequestMetadataDto();
            var digitalRequestMetadataDto = new DigitalRequestMetadataDto();
            digitalRequestMetadataDto.setCorrelationId(digitalNotificationRequest.getCorrelationId());
            digitalRequestMetadataDto.setEventType(digitalNotificationRequest.getEventType());
            digitalRequestMetadataDto.setTags(digitalNotificationRequest.getTags());
            digitalRequestMetadataDto.setChannel(PEC);
            digitalRequestMetadataDto.setMessageContentType(PLAIN);
            requestMetadataDto.setDigitalRequestMetadata(digitalRequestMetadataDto);

            requestDto.setRequestPersonal(requestPersonalDto);
            requestDto.setRequestMetadata(requestMetadataDto);
            return requestDto;
        }).flatMap(gestoreRepositoryCall::insertRichiesta);
    }

    @SqsListener(value = "${sqs.queue.pec.interactive-name}", deletionPolicy = SqsMessageDeletionPolicy.NEVER)
    public void lavorazioneRichiesta(final PecPresaInCaricoInfo pecPresaInCaricoInfo, final Acknowledgment acknowledgment) throws MessagingException {

        log.info("<-- START LAVORAZIONE RICHIESTA PEC -->");
        logIncomingMessage(pecSqsQueueName.interactiveName(), pecPresaInCaricoInfo);

        var requestId = pecPresaInCaricoInfo.getRequestIdx();
        var clientId = pecPresaInCaricoInfo.getXPagopaExtchCxId();
        var digitalNotificationRequest = pecPresaInCaricoInfo.getDigitalNotificationRequest();

        var messageId = encodeMessageId(requestId,clientId);

        String data = createMimeMassage(digitalNotificationRequest,messageId);


//      Try to send PEC
        var sendMail = new SendMail();
        // TODO finire di valorizzare
        sendMail.setData(data);

        AtomicReference<GeneratedMessageDto> generatedMessageDto = new AtomicReference<>();


        attachmentService.checkAllegatiPresence(digitalNotificationRequest.getAttachmentsUrls(),
                clientId,
                false);



        arubaCall.sendMail(sendMail)



//                       The PEC in sent, publish to Notification Tracker with next status -> SENT
                .flatMap(sendMailResponse -> {
                    // TODO check with sms - come implementare il messageId della pec
                    generatedMessageDto.set(new GeneratedMessageDto().id(messageId).system("systemPlaceholder"));
                    return sqsService.send(notificationTrackerSqsName.statoPecName(),
                            new NotificationTrackerQueueDto(requestId,
                                    clientId,
                                    now(),
                                    transactionProcessConfigurationProperties.pec(),
                                    pecPresaInCaricoInfo.getStatusAfterStart(),
                                    "sent",
                                    // TODO: SET eventDetails
                                    "",
                                    generatedMessageDto.get()));
                })

//                Delete from queue
                .doOnSuccess(result -> acknowledgment.acknowledge())

//                 An error occurred during PEC send, start retries
                .onErrorResume(ArubaSendException.class,
                        arubaSendException -> retryPecSend(acknowledgment,
                                pecPresaInCaricoInfo,
                                pecPresaInCaricoInfo.getStatusAfterStart(),
                                generatedMessageDto.get()))

//                An error occurred during SQS publishing to the Notification Tracker -> Publish to Errori PEC queue and
//                notify to retry update status only
//                TODO: CHANGE THE PAYLOAD
                .onErrorResume(SqsPublishException.class,
                        sqsPublishException -> sqsService.send(pecSqsQueueName.errorName(), pecPresaInCaricoInfo)).subscribe();
    }

    // TODO cambiare tipo ritorno metodo??
    private Mono<SendMessageResponse> retryPecSend(final Acknowledgment acknowledgment,
                                                   final PecPresaInCaricoInfo pecPresaInCaricoInfo, final String currentStatus,
                                                   final GeneratedMessageDto generateMessageDto) {

        var requestId = pecPresaInCaricoInfo.getRequestIdx();
        var clientId = pecPresaInCaricoInfo.getXPagopaExtchCxId();

//      Try to send PEC - ARUBA?
        var sendMail = new SendMail();
        // TODO valorizzare sendMail
        return arubaCall.sendMail(sendMail)

//                       Retry to send PEC
                .retryWhen(DEFAULT_RETRY_STRATEGY)

//                       The PEC in sent, publish to Notification Tracker with next status -> SENT
                .flatMap(sendMailResponse -> sqsService.send(notificationTrackerSqsName.statoPecName(),
                        new NotificationTrackerQueueDto(requestId, clientId, now(),
                                transactionProcessConfigurationProperties.pec(), currentStatus, "sent",
                                // TODO: SET eventDetails
                                "",
                                // TODO: check with sms
                                new GeneratedMessageDto().id(sendMailResponse.getErrstr())
                                        .system("")
                                        .location(""))))

//                       Delete from queue
                .doOnSuccess(result -> acknowledgment.acknowledge())

//                       The maximum number of retries has ended
                .onErrorResume(ArubaSendException.ArubaMaxRetriesExceededException.class,
                        arubaMaxRetriesExceeded -> pecRetriesExceeded(acknowledgment, pecPresaInCaricoInfo,
                                generateMessageDto, currentStatus));
    }

    private Mono<SendMessageResponse> pecRetriesExceeded(final Acknowledgment acknowledgment,
                                                         final PecPresaInCaricoInfo pecPresaInCaricoInfo,
                                                         final GeneratedMessageDto generatedMessageDto,
                                                         String currentStatus
    ) {

        var requestId = pecPresaInCaricoInfo.getRequestIdx();
        var clientId = pecPresaInCaricoInfo.getXPagopaExtchCxId();

        // Publish to Notification Tracker with next status -> RETRY
        return sqsService.send(notificationTrackerSqsName.statoPecName(),
                               new NotificationTrackerQueueDto(requestId,
                                                               clientId,
                                                               now(),
                                                               transactionProcessConfigurationProperties.pec(),
                                                               currentStatus,
                                                               "retry",
                                                               // TODO: SET eventDetails,
                                                               "",
                                                               generatedMessageDto))

                // Publish to ERRORI PEC queue
                .then(sqsService.send(pecSqsQueueName.errorName(), pecPresaInCaricoInfo))

                // Delete from queue
                .doOnSuccess(result -> acknowledgment.acknowledge());
    }




    private String createMimeMassage(DigitalNotificationRequest digitalNotificationRequest, String messageId) throws MessagingException {

        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        mimeMessage.saveChanges();
        mimeMessage.setHeader("Message-ID", messageId);
        mimeMessage.getSentDate();

        mimeMessage.setFrom(digitalNotificationRequest.getReceiverDigitalAddress());
        mimeMessage.setSender(new InternetAddress(digitalNotificationRequest.getSenderDigitalAddress()));
        mimeMessage.setRecipient(Message.RecipientType.TO, new InternetAddress("topolino@pluto.com"));
        mimeMessage.setContent("Prova di invio mail", "text/plain");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            mimeMessage.writeTo(output);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        String rawEmail = output.toString();
        System.out.println(rawEmail);
        return rawEmail;
    }



}
