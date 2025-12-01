package com.synclab.miloserver.opcua;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;

/**
 * 공장별 실내 온습도 센서 역할을 하는 OPC 노드 시뮬레이터.
 * 기준값 대비 ±1% 오차 범위에서 10분 간격으로 갱신되는 JSON payload를 제공합니다.
 */
public class EnvironmentProbe {

    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(10);
    private static final AtomicInteger THREAD_SEQ = new AtomicInteger();

    private final MultiMachineNameSpace namespace;
    private final UaVariableNode payloadNode;
    private final String factoryCode;
    private final double baseTemperature;
    private final double baseHumidity;
    private final Duration interval;
    private final ScheduledExecutorService scheduler;

    public EnvironmentProbe(MultiMachineNameSpace namespace,
                            UaFolderNode parentFolder,
                            String factoryCode,
                            double baseTemperature,
                            double baseHumidity,
                            Duration interval) {
        this.namespace = namespace;
        this.factoryCode = factoryCode;
        this.baseTemperature = baseTemperature;
        this.baseHumidity = baseHumidity;
        this.interval = interval != null ? interval : DEFAULT_INTERVAL;
        UaFolderNode probeFolder = namespace.addFolder(parentFolder, "Environment");
        this.payloadNode = namespace.addVariableNode(
                probeFolder,
                "environment_payload",
                initialPayload()
        );
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "environment-probe-" + factoryCode + "-" + THREAD_SEQ.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public EnvironmentProbe(MultiMachineNameSpace namespace,
                            UaFolderNode parentFolder,
                            String factoryCode,
                            double baseTemperature,
                            double baseHumidity) {
        this(namespace, parentFolder, factoryCode, baseTemperature, baseHumidity, DEFAULT_INTERVAL);
    }

    private String initialPayload() {
        return createPayload(baseTemperature, baseHumidity);
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::publishReading, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void publishReading() {
        double temperature = randomizeAround(baseTemperature);
        double humidity = randomizeAround(baseHumidity);
        String payload = createPayload(temperature, humidity);
        namespace.updateValue(payloadNode, payload);
    }

    private double randomizeAround(double base) {
        double tolerance = Math.abs(base) * 0.01;
        double offset = ThreadLocalRandom.current().nextDouble(-tolerance, tolerance);
        return base + offset;
    }

    private String createPayload(double temperature, double humidity) {
        BigDecimal roundedTemp = BigDecimal.valueOf(temperature).setScale(2, RoundingMode.HALF_UP);
        BigDecimal roundedHum = BigDecimal.valueOf(humidity).setScale(2, RoundingMode.HALF_UP);
        long timestamp = Instant.now().toEpochMilli();
        return String.format(
                "{\"factory_code\":\"%s\",\"temperature\":%s,\"humidity\":%s,\"timestamp\":%d}",
                factoryCode,
                roundedTemp.toPlainString(),
                roundedHum.toPlainString(),
                timestamp
        );
    }
}
