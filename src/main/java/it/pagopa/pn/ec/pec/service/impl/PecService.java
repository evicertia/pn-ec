package it.pagopa.pn.ec.pec.service.impl;

import io.awspring.cloud.messaging.listener.Acknowledgment;
import io.awspring.cloud.messaging.listener.SqsMessageDeletionPolicy;
import io.awspring.cloud.messaging.listener.annotation.SqsListener;
import it.pagopa.pn.ec.commons.configurationproperties.TransactionProcessConfigurationProperties;
import it.pagopa.pn.ec.commons.configurationproperties.sqs.NotificationTrackerSqsName;
import it.pagopa.pn.ec.commons.exception.EcInternalEndpointHttpException;
import it.pagopa.pn.ec.commons.exception.aruba.ArubaSendException;
import it.pagopa.pn.ec.commons.exception.sqs.SqsPublishException;
import it.pagopa.pn.ec.commons.model.pojo.PresaInCaricoInfo;
import it.pagopa.pn.ec.commons.model.pojo.email.EmailAttachment;
import it.pagopa.pn.ec.commons.model.pojo.email.EmailField;
import it.pagopa.pn.ec.commons.rest.call.aruba.ArubaCall;
import it.pagopa.pn.ec.commons.rest.call.download.DownloadCall;
import it.pagopa.pn.ec.commons.rest.call.ec.gestorerepository.GestoreRepositoryCall;
import it.pagopa.pn.ec.commons.service.AuthService;
import it.pagopa.pn.ec.commons.service.PresaInCaricoService;
import it.pagopa.pn.ec.commons.service.SqsService;
import it.pagopa.pn.ec.commons.service.impl.AttachmentServiceImpl;
import it.pagopa.pn.ec.commons.utils.EmailUtils;
import it.pagopa.pn.ec.pec.configurationproperties.PecSqsQueueName;
import it.pagopa.pn.ec.pec.model.pojo.ArubaSecretValue;
import it.pagopa.pn.ec.pec.model.pojo.PecPresaInCaricoInfo;
import it.pagopa.pn.ec.rest.v1.dto.*;
import it.pec.bridgews.SendMail;
import it.pec.bridgews.SendMailResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.time.Duration;

import static it.pagopa.pn.ec.commons.model.dto.NotificationTrackerQueueDto.createNotificationTrackerQueueDtoDigital;
import static it.pagopa.pn.ec.commons.utils.SqsUtils.logIncomingMessage;
import static it.pagopa.pn.ec.pec.utils.MessageIdUtils.encodeMessageId;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalNotificationRequest.QosEnum.BATCH;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalNotificationRequest.QosEnum.INTERACTIVE;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestMetadataDto.ChannelEnum.PEC;

@Service
@Slf4j
public class PecService extends PresaInCaricoService {

    private final SqsService sqsService;
    private final ArubaCall arubaCall;
    private final GestoreRepositoryCall gestoreRepositoryCall;
    private final AttachmentServiceImpl attachmentService;
    private final DownloadCall downloadCall;
    private final ArubaSecretValue arubaSecretValue;
    private final NotificationTrackerSqsName notificationTrackerSqsName;
    private final PecSqsQueueName pecSqsQueueName;
    private final TransactionProcessConfigurationProperties transactionProcessConfigurationProperties;

    protected PecService(AuthService authService, ArubaCall arubaCall, GestoreRepositoryCall gestoreRepositoryCall, SqsService sqsService
            , AttachmentServiceImpl attachmentService, DownloadCall downloadCall, ArubaSecretValue arubaSecretValue,
                         NotificationTrackerSqsName notificationTrackerSqsName, PecSqsQueueName pecSqsQueueName,
                         TransactionProcessConfigurationProperties transactionProcessConfigurationProperties) {
        super(authService, gestoreRepositoryCall);
        this.arubaCall = arubaCall;
        this.sqsService = sqsService;
        this.gestoreRepositoryCall = gestoreRepositoryCall;
        this.attachmentService = attachmentService;
        this.downloadCall = downloadCall;
        this.arubaSecretValue = arubaSecretValue;
        this.notificationTrackerSqsName = notificationTrackerSqsName;
        this.pecSqsQueueName = pecSqsQueueName;
        this.transactionProcessConfigurationProperties = transactionProcessConfigurationProperties;
    }

    @Override
    protected Mono<Void> specificPresaInCarico(final PresaInCaricoInfo presaInCaricoInfo) {
//      Cast PresaInCaricoInfo to specific PecPresaInCaricoInfo
        var pecPresaInCaricoInfo = (PecPresaInCaricoInfo) presaInCaricoInfo;
        var requestIdx = pecPresaInCaricoInfo.getRequestIdx();
        var xPagopaExtchCxId = pecPresaInCaricoInfo.getXPagopaExtchCxId();

        var digitalNotificationRequest = pecPresaInCaricoInfo.getDigitalNotificationRequest();
        digitalNotificationRequest.setRequestId(requestIdx);

        return attachmentService.getAllegatiPresignedUrlOrMetadata(pecPresaInCaricoInfo.getDigitalNotificationRequest()
                                                                                       .getAttachmentsUrls(), xPagopaExtchCxId, true)

                                .then(insertRequestFromPec(digitalNotificationRequest,
                                                           xPagopaExtchCxId).onErrorResume(throwable -> Mono.error(new EcInternalEndpointHttpException())))

                                .flatMap(requestDto -> sqsService.send(notificationTrackerSqsName.statoPecName(),
                                                                       createNotificationTrackerQueueDtoDigital(presaInCaricoInfo,
                                                                                                                transactionProcessConfigurationProperties.pecStartStatus(),
                                                                                                                "booked",
                                                                                                                new DigitalProgressStatusDto()))

//                                                               Publish to PEC INTERACTIVE or PEC BATCH
                                                                 .flatMap(sendMessageResponse -> {
                                                                     DigitalNotificationRequest.QosEnum qos =
                                                                             pecPresaInCaricoInfo.getDigitalNotificationRequest().getQos();
                                                                     if (qos == INTERACTIVE) {
                                                                         return sqsService.send(pecSqsQueueName.interactiveName(),
                                                                                                pecPresaInCaricoInfo);
                                                                     } else if (qos == BATCH) {
                                                                         return sqsService.send(pecSqsQueueName.batchName(),
                                                                                                pecPresaInCaricoInfo);
                                                                     } else {
                                                                         return Mono.empty();
                                                                     }
                                                                 })).then();
    }

    @SuppressWarnings("Duplicates")
    private Mono<RequestDto> insertRequestFromPec(final DigitalNotificationRequest digitalNotificationRequest, String xPagopaExtchCxId) {
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
            digitalRequestMetadataDto.setMessageContentType(DigitalRequestMetadataDto.MessageContentTypeEnum.PLAIN);
            requestMetadataDto.setDigitalRequestMetadata(digitalRequestMetadataDto);

            requestDto.setRequestPersonal(requestPersonalDto);
            requestDto.setRequestMetadata(requestMetadataDto);
            return requestDto;
        }).flatMap(gestoreRepositoryCall::insertRichiesta);
    }

    @SqsListener(value = "${sqs.queue.pec.interactive-name}", deletionPolicy = SqsMessageDeletionPolicy.NEVER)
    public void lavorazioneRichiestaListener(final PecPresaInCaricoInfo pecPresaInCaricoInfo, final Acknowledgment acknowledgment) {

        log.info("<-- START LAVORAZIONE RICHIESTA PEC -->");
        logIncomingMessage(pecSqsQueueName.interactiveName(), pecPresaInCaricoInfo);

        lavorazioneRichiesta(pecPresaInCaricoInfo).doOnNext(result -> acknowledgment.acknowledge()).subscribe();
    }

    private static final Retry LAVORAZIONE_RICHIESTA_RETRY_STRATEGY = Retry.backoff(3, Duration.ofSeconds(2));

    Mono<SendMessageResponse> lavorazioneRichiesta(final PecPresaInCaricoInfo pecPresaInCaricoInfo) {

        var requestIdx = pecPresaInCaricoInfo.getRequestIdx();
        var xPagopaExtchCxId = pecPresaInCaricoInfo.getXPagopaExtchCxId();
        var digitalNotificationRequest = pecPresaInCaricoInfo.getDigitalNotificationRequest();

//      Get attachment presigned url Flux
        return attachmentService.getAllegatiPresignedUrlOrMetadata(digitalNotificationRequest.getAttachmentsUrls(), xPagopaExtchCxId, false)
                                .retryWhen(LAVORAZIONE_RICHIESTA_RETRY_STRATEGY)

                                .filter(fileDownloadResponse -> fileDownloadResponse.getDownload() != null)

                                .flatMap(fileDownloadResponse -> downloadCall.downloadFile(fileDownloadResponse.getDownload().getUrl())
                                                                             .retryWhen(LAVORAZIONE_RICHIESTA_RETRY_STRATEGY)
                                                                             .map(outputStream -> EmailAttachment.builder()
                                                                                                                 .nameWithExtension(
                                                                                                                         fileDownloadResponse.getKey())
                                                                                                                 .content(outputStream)
                                                                                                                 .build()))

//                              Convert to Mono<List>
                                .collectList()

//                              Create EmailField object with request info and attachments
                                .map(fileDownloadResponses -> EmailField.builder()
                                                                        .msgId(encodeMessageId(requestIdx, xPagopaExtchCxId))
                                                                        .from(arubaSecretValue.getPecUsername())
                                                                        .to(digitalNotificationRequest.getReceiverDigitalAddress())
                                                                        .subject(digitalNotificationRequest.getSubjectText())
                                                                        .text(digitalNotificationRequest.getMessageText())
                                                                        .contentType(digitalNotificationRequest.getMessageContentType()
                                                                                                               .getValue())
                                                                        .emailAttachments(fileDownloadResponses)
                                                                        .build())

                                .map(EmailUtils::getMimeMessageInCDATATag)

                                .flatMap(mimeMessageInCdata -> {
                                    var sendMail = new SendMail();
                                    sendMail.setData(mimeMessageInCdata);
                                    return arubaCall.sendMail(sendMail);
                                })

                                .handle((sendMailResponse, sink) -> {
                                    if (sendMailResponse.getErrcode() != 0) {
                                        sink.error(new ArubaSendException());
                                    } else {
                                        sink.next(sendMailResponse);
                                    }
                                })

                                .cast(SendMailResponse.class)

                                .map(this::createGeneratedMessageDto)

                                .zipWhen(generatedMessageDto -> gestoreRepositoryCall.setMessageIdInRequestMetadata(requestIdx))

                                .flatMap(objects -> sqsService.send(notificationTrackerSqsName.statoPecName(),
                                                                    createNotificationTrackerQueueDtoDigital(pecPresaInCaricoInfo,
                                                                                                             "booked",
                                                                                                             "sent",
                                                                                                             new DigitalProgressStatusDto().generatedMessage(
                                                                                                                     objects.getT1())))
                                                              .retryWhen(LAVORAZIONE_RICHIESTA_RETRY_STRATEGY)

//                                                            An error occurred during SQS publishing to the Notification Tracker ->
//                                                            Publish to Errori PEC queue and notify to retry update status only
//                                                            TODO: CHANGE THE PAYLOAD
                                                              .onErrorResume(SqsPublishException.class,
                                                                             sqsPublishException -> sqsService.send(pecSqsQueueName.errorName(),
                                                                                                                    pecPresaInCaricoInfo)))
                                .doOnError(throwable -> {
                                    log.info("An error occurred during lavorazione PEC");
                                    log.error(throwable.getMessage());
                                })

                                .onErrorResume(throwable -> sqsService.send(notificationTrackerSqsName.statoPecName(),
                                                                            createNotificationTrackerQueueDtoDigital(pecPresaInCaricoInfo,
                                                                                                                     "booked",
                                                                                                                     "retry",
                                                                                                                     new DigitalProgressStatusDto()))

//                                                                    Publish to ERRORI PEC queue
                                                                      .then(sqsService.send(pecSqsQueueName.errorName(),
                                                                                            pecPresaInCaricoInfo)));
    }

    private GeneratedMessageDto createGeneratedMessageDto(SendMailResponse sendMailResponse) {
        var errstr = sendMailResponse.getErrstr();
//      Remove the last 2 char '\r\n'
        return new GeneratedMessageDto().id(errstr.substring(0, errstr.length() - 2)).system("toBeDefined");
    }
}
