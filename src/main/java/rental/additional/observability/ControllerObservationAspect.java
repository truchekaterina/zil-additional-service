package rental.additional.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class ControllerObservationAspect {

    private final ObservabilityService observabilityService;

    public ControllerObservationAspect(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    @Around("execution(* rental.additional.controller..*.*(..))")
    public Object measureController(ProceedingJoinPoint joinPoint) throws Throwable {
        String simple = joinPoint.getSignature().getDeclaringType().getSimpleName();
        String method = joinPoint.getSignature().getName();
        String category = "controller." + simple + "." + method;
        long t0 = System.nanoTime();
        try {
            return joinPoint.proceed();
        } finally {
            observabilityService.record(category, System.nanoTime() - t0);
        }
    }
}
