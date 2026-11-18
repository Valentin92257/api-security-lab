package lab.detector;

import java.time.Clock;

import lab.detector.analysis.AnalysisService;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DetectorApplication {
    public static void main(String[] args) {
        SpringApplication.run(DetectorApplication.class, args);
    }

    @Bean
    Clock detectorClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "lab.detector.recovery-enabled", havingValue = "true", matchIfMissing = true)
    SmartInitializingSingleton interruptPreviousAnalyses(AnalysisService analyses) {
        return analyses::interruptPrevious;
    }
}
