package com.apm.agent.generator;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class JfrCpuLoadInjector {

    private final ChaosStateService chaosStateService;
    private final Random random = new Random();

    public String generateFoldedProfile(String serviceName, boolean injectChaosSpike) {
        boolean activeCpuSpike = chaosStateService.isScenarioActive(ChaosStateService.ChaosScenario.CPU_SPIKE) || injectChaosSpike;
        double intensity = chaosStateService.getStatus().intensity();

        if (activeCpuSpike) {
            // Perform small background math cycle to simulate actual CPU burning without crashing machine
            burnCpuCycles((int) (intensity * 50_000));
        }

        StringBuilder sb = new StringBuilder();

        // Baseline Spring Boot / Netty server runtime stack
        String prefix = "java.lang.Thread.run;org.springframework.boot.web.embedded.netty.NettyWebServer.start;io.netty.channel.nio.NioEventLoop.run;";

        // Common framework operations
        sb.append(prefix).append("org.springframework.web.reactive.DispatcherHandler.handle;com.apm.").append(serviceName).append(".controller.handleRequest ")
                .append(150 + random.nextInt(50)).append("\n");

        sb.append(prefix).append("com.apm.").append(serviceName).append(".controller.handleRequest;com.apm.").append(serviceName).append(".service.validatePayload;com.fasterxml.jackson.databind.ObjectMapper.readValue ")
                .append(80 + random.nextInt(30)).append("\n");

        if (activeCpuSpike) {
            int spikeMultiplier = (int) (1 + intensity * 3);

            // Chaos 1: Catastrophic Regex Backtracking
            sb.append(prefix).append("com.apm.").append(serviceName).append(".service.validateOrder;com.apm.common.regex.PatternMatcher.matches;java.util.regex.Matcher.find;java.util.regex.Pattern.matcher ")
                    .append((1200 + random.nextInt(500)) * spikeMultiplier).append("\n");

            // Chaos 2: CPU-heavy Key Derivation / BCrypt
            sb.append(prefix).append("com.apm.").append(serviceName).append(".security.AuthValidator.verifyToken;com.apm.common.crypto.BCrypt.hashPassword;org.bouncycastle.crypto.generators.SCrypt.generate ")
                    .append((950 + random.nextInt(400)) * spikeMultiplier).append("\n");

            // Chaos 3: Unindexed DB Full Table Scan
            sb.append(prefix).append("com.apm.").append(serviceName).append(".repository.findActiveTransactions;com.clickhouse.jdbc.internal.ClickHouseConnection.query;java.net.SocketInputStream.read ")
                    .append((800 + random.nextInt(300)) * spikeMultiplier).append("\n");
        } else {
            // Normal baseline hot paths
            sb.append(prefix).append("com.apm.").append(serviceName).append(".service.processBusinessLogic;com.apm.").append(serviceName).append(".repository.findById;com.clickhouse.jdbc.internal.ClickHouseConnection.query ")
                    .append(210 + random.nextInt(60)).append("\n");

            sb.append(prefix).append("com.apm.").append(serviceName).append(".service.processBusinessLogic;com.apm.common.crypto.AES.encrypt ")
                    .append(65 + random.nextInt(20)).append("\n");

            sb.append(prefix).append("com.apm.").append(serviceName).append(".controller.handleRequest;com.fasterxml.jackson.databind.ObjectMapper.writeValueAsString ")
                    .append(110 + random.nextInt(40)).append("\n");
        }

        // Garbage collection & JIT compiler background frames
        sb.append("CompilerThread0;jdk.internal.vm.compiler.CompileBroker.invokeCompilerOnMethod;org.graalvm.compiler.core.GraalCompiler.compile ")
                .append(25 + random.nextInt(15)).append("\n");

        sb.append("GC-Thread;jdk.internal.vm.gc.G1CollectedHeap.do_collection_pause_at_safepoint;jdk.internal.vm.gc.G1ParScanThreadState.trim_queue ")
                .append(40 + random.nextInt(20)).append("\n");

        return sb.toString();
    }

    private void burnCpuCycles(int iterations) {
        long result = 0;
        for (int i = 0; i < iterations; i++) {
            result += (long) Math.sqrt(ThreadLocalRandom.current().nextDouble() * 1000.0);
        }
    }
}
