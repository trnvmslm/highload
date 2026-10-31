package kz.highload.polls.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import kz.highload.polls.fault.FaultInjector;

public class FaultInjectionInterceptor implements ServerInterceptor {
    private final FaultInjector faultInjector;

    public FaultInjectionInterceptor(FaultInjector faultInjector) {
        this.faultInjector = faultInjector;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        if (faultInjector.shouldFail()) {
            call.close(Status.UNAVAILABLE.withDescription("Injected fault"), new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return next.startCall(call, headers);
    }
}
