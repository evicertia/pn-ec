package it.pagopa.pn.ec.pec.service.impl;

import it.pagopa.pn.ec.commons.exception.EcInternalEndpointHttpException;
import it.pagopa.pn.ec.commons.model.dto.NotificationTrackerQueueDto;
import it.pagopa.pn.ec.commons.model.pojo.PresaInCaricoInfo;
import it.pagopa.pn.ec.commons.rest.call.gestorerepository.GestoreRepositoryCall;
import it.pagopa.pn.ec.commons.service.AuthService;
import it.pagopa.pn.ec.commons.service.PresaInCaricoService;
import it.pagopa.pn.ec.commons.service.SqsService;
import it.pagopa.pn.ec.commons.service.attachments.CheckAttachments;
import it.pagopa.pn.ec.pec.model.pojo.PecPresaInCaricoInfo;
import it.pagopa.pn.ec.rest.v1.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static it.pagopa.pn.ec.commons.constant.ProcessId.INVIO_PEC;
import static it.pagopa.pn.ec.commons.constant.QueueNameConstant.*;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalNotificationRequest.QosEnum.BATCH;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalNotificationRequest.QosEnum.INTERACTIVE;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestMetadataDto.ChannelEnum.PEC;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestMetadataDto.MessageContentTypeEnum.PLAIN;
import static it.pagopa.pn.ec.rest.v1.dto.DigitalRequestStatus.BOOKED;

@Service
@Slf4j
public class PecService extends PresaInCaricoService {

    private final SqsService sqsService;

    private final GestoreRepositoryCall gestoreRepositoryCall;

    private final CheckAttachments checkAttachments;

    protected PecService(AuthService authService, GestoreRepositoryCall gestoreRepositoryCall, SqsService sqsService,
                         CheckAttachments checkAttachments) {
        super(authService, gestoreRepositoryCall);
        this.sqsService = sqsService;
        this.gestoreRepositoryCall = gestoreRepositoryCall;
        this.checkAttachments = checkAttachments;
    }

    @Override
    protected Mono<Void> specificPresaInCarico(final PresaInCaricoInfo presaInCaricoInfo) {
//      Cast PresaInCaricoInfo to specific SmsPresaInCaricoInfo
        PecPresaInCaricoInfo pecPresaInCaricoInfo = (PecPresaInCaricoInfo) presaInCaricoInfo;
        return checkAttachments.checkAllegatiPresence(pecPresaInCaricoInfo.getDigitalNotificationRequest().getAttachmentsUrls(),
                                                      presaInCaricoInfo.getXPagopaExtchCxId(),
                                                      false)
                               .flatMap(fileDownloadResponse -> {
                                   var digitalNotificationRequest = pecPresaInCaricoInfo.getDigitalNotificationRequest();
                                   digitalNotificationRequest.setRequestId(presaInCaricoInfo.getRequestIdx());
                                   return insertRequestFromPec(digitalNotificationRequest).onErrorResume(throwable -> Mono.error(new EcInternalEndpointHttpException()));
                               })
                               .flatMap(requestDto -> sqsService.send(NT_STATO_PEC_QUEUE_NAME,
                                                                      new NotificationTrackerQueueDto(presaInCaricoInfo.getRequestIdx(),
                                                                                                      presaInCaricoInfo.getXPagopaExtchCxId(),
                                                                                                      INVIO_PEC,
                                                                                                      null,
                                                                                                      BOOKED.getValue())))
                               .flatMap(sendMessageResponse -> {
                                   DigitalNotificationRequest.QosEnum qos = pecPresaInCaricoInfo.getDigitalNotificationRequest().getQos();
                                   if (qos == INTERACTIVE) {
                                       return sqsService.send(PEC_INTERACTIVE_QUEUE_NAME,
                                                              pecPresaInCaricoInfo.getDigitalNotificationRequest());
                                   } else if (qos == BATCH) {
                                       return sqsService.send(PEC_BATCH_QUEUE_NAME, pecPresaInCaricoInfo.getDigitalNotificationRequest());
                                   } else {
                                       return Mono.empty();
                                   }
                               })
                               .then();
    }

    private Mono<RequestDto> insertRequestFromPec(final DigitalNotificationRequest digitalNotificationRequest) {
        return Mono.fromCallable(() -> {
            var requestDto = new RequestDto();
            requestDto.setRequestIdx(digitalNotificationRequest.getRequestId());
            requestDto.setClientRequestTimeStamp(digitalNotificationRequest.getClientRequestTimeStamp());

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
}
