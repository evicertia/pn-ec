package it.pagopa.pn.ec.repositorymanager.service.impl;

import it.pagopa.pn.ec.repositorymanager.model.entity.Events;
import it.pagopa.pn.ec.repositorymanager.model.entity.RequestMetadata;
import it.pagopa.pn.ec.repositorymanager.model.entity.RequestPersonal;
import it.pagopa.pn.ec.repositorymanager.model.pojo.Request;
import it.pagopa.pn.ec.repositorymanager.service.RequestMetadataService;
import it.pagopa.pn.ec.repositorymanager.service.RequestPersonalService;
import it.pagopa.pn.ec.repositorymanager.service.RequestService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

@Service
@Slf4j
public class RequestServiceImpl implements RequestService {


    private final RequestPersonalService requestPersonalService;
    private final RequestMetadataService requestMetadataService;

    public RequestServiceImpl(RequestPersonalService requestPersonalService, RequestMetadataService requestMetadataService) {
        this.requestPersonalService = requestPersonalService;
        this.requestMetadataService = requestMetadataService;
    }

    private Request createRequestFromPersonalAndMetadata(String requestId, RequestPersonal requestPersonal,
                                                         RequestMetadata requestMetadata) {
        String currentStatus = requestMetadata.getStatusRequest();
        OffsetDateTime clientRequestTimeStamp = requestMetadata.getClientRequestTimeStamp();
        OffsetDateTime requestTimeStamp = requestMetadata.getRequestTimestamp();
        return Request.builder()
                      .requestId(requestId)
                      .statusRequest(currentStatus)
                      .clientRequestTimeStamp(clientRequestTimeStamp)
                      .requestTimeStamp(requestTimeStamp)
                      .requestPersonal(requestPersonal)
                      .requestMetadata(requestMetadata)
                      .build();
    }

    @Override
    public Mono<Request> getRequest(String requestIdx) {
        return Mono.zip(requestPersonalService.getRequestPersonal(requestIdx), requestMetadataService.getRequestMetadata(requestIdx))
                   .map(objects -> {
                       RequestPersonal retrievedRequestPersonal = objects.getT1();
                       RequestMetadata retrievedRequestMetadata = objects.getT2();
                       return createRequestFromPersonalAndMetadata(requestIdx, retrievedRequestPersonal, retrievedRequestMetadata);
                   });
    }

    @Override
    public Mono<Request> insertRequest(Request request) {

        String requestId = request.getRequestId();

        RequestPersonal requestPersonal = request.getRequestPersonal();
        requestPersonal.setRequestId(requestId);
        requestPersonal.setClientRequestTimeStamp(request.getClientRequestTimeStamp());
        requestPersonal.setRequestTimestamp(OffsetDateTime.now());

        RequestMetadata requestMetadata = request.getRequestMetadata();
        requestMetadata.setRequestId(requestId);
        requestMetadata.setClientRequestTimeStamp(request.getClientRequestTimeStamp());
        requestMetadata.setRequestTimestamp(OffsetDateTime.now());

        return Mono.zip(requestPersonalService.insertRequestPersonal(requestPersonal),
                        requestMetadataService.insertRequestMetadata(requestMetadata)).map(objects -> {
            RequestPersonal insertedRequestPersonal = objects.getT1();
            RequestMetadata insertedRequestMetadata = objects.getT2();
            return createRequestFromPersonalAndMetadata(requestId, insertedRequestPersonal, insertedRequestMetadata);
        });
    }

    @Override
    public Mono<Request> updateEvents(String requestIdx, Events events) {
        return Mono.zip(requestPersonalService.updateEventsPersonal(requestIdx, events.getEventsPersonal()),
                        requestMetadataService.updateEventsMetadata(requestIdx, events.getEventsMetadata())).map(objects -> {
            RequestPersonal updatedRequestPersonal = objects.getT1();
            RequestMetadata updatedRequestMetadata = objects.getT2();
            return createRequestFromPersonalAndMetadata(requestIdx, updatedRequestPersonal, updatedRequestMetadata);
        });
    }

    @Override
    public Mono<Request> deleteRequest(String requestIdx) {
        return Mono.zip(requestPersonalService.deleteRequestPersonal(requestIdx), requestMetadataService.deleteRequestMetadata(requestIdx))
                   .map(objects -> {
                       RequestPersonal deletedRequestPersonal = objects.getT1();
                       RequestMetadata deletedRequestMetadata = objects.getT2();
                       return createRequestFromPersonalAndMetadata(requestIdx, deletedRequestPersonal, deletedRequestMetadata);
                   });
    }
}
