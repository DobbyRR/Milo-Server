package com.synclab.miloserver.machine.cylindricalLine.finalInspection;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

import java.util.concurrent.ThreadLocalRandom;

public class FinalInspection02 extends UnitLogic {

    private static final double[] STAGE_DURATIONS_SEC = {2.0, 2.0, 2.0};
    private static final double TOTAL_CYCLE_TIME_SEC = 6.0;
    private static final int SIMULATION_SPEED = 5;

    private int stageIndex = 0;
    private double stageElapsed = 0.0;
    private double cycleElapsed = 0.0;
    private double totalElapsedSeconds = 0.0;
    private int processedSerialCount = 0;
    private boolean currentSerialOkFlag = true;
    private int currentNgType = 0;

    private double visionScore = 0.0;
    private double electricalResistance = 0.0;
    private boolean safetyPassed = true;
    private boolean functionPassed = true;
    private String lotCode = "";

    /**
     * Final Inspection NG Type codes (1~4)
     * 1 - 비전 검사 불량
     * 2 - 전기 저항 불량
     * 3 - 안전 검사 실패
     * 4 - 기능 검사 실패
     */
    private static final class NgType {
        static final int VISION_FAIL = 1;
        static final int RESISTANCE_FAIL = 2;
        static final int SAFETY_FAIL = 3;
        static final int FUNCTION_FAIL = 4;

        private NgType() {}
    }

    public FinalInspection02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "FINAL_INSPECTION";
        this.lineId = "CylindricalLine";
        this.machineNo = 7;
        this.equipmentId = "FI-02";
        this.processId = "FinalInspection";
        this.defaultPpm = 52;
        setUnitsPerCycle(1);
        configureEnergyProfile(0.32, 0.04, 3.6, 0.45);

        setupCommonTelemetry(ns);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("vision_score", ns.addVariableNode(machineFolder, name + ".vision_score", 0.0));
        telemetryNodes.put("electrical_resistance", ns.addVariableNode(machineFolder, name + ".electrical_resistance", 0.0));
        telemetryNodes.put("safety_passed", ns.addVariableNode(machineFolder, name + ".safety_passed", true));
        telemetryNodes.put("function_passed", ns.addVariableNode(machineFolder, name + ".function_passed", true));
        telemetryNodes.put("lot_verified", ns.addVariableNode(machineFolder, name + ".lot_verified", ""));
        telemetryNodes.put("current_serial", ns.addVariableNode(machineFolder, name + ".current_serial", ""));
        telemetryNodes.put("serial_ok", ns.addVariableNode(machineFolder, name + ".serial_ok", true));
        telemetryNodes.put("ng_type", ns.addVariableNode(machineFolder, name + ".ng_type", 0));
        telemetryNodes.put("cycle_time_sec", ns.addVariableNode(machineFolder, name + ".cycle_time_sec", TOTAL_CYCLE_TIME_SEC));
        telemetryNodes.put("t_in_cycle_sec", ns.addVariableNode(machineFolder, name + ".t_in_cycle_sec", 0.0));
        telemetryNodes.put("processed_count", ns.addVariableNode(machineFolder, name + ".processed_count", 0));
        telemetryNodes.put("good_count", ns.addVariableNode(machineFolder, name + ".good_count", 0));
        telemetryNodes.put("ng_count", ns.addVariableNode(machineFolder, name + ".ng_count", 0));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[FinalInspection02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                applyIdleDrift(ns);
                break;
            case "STARTING":
                if (timeInState(2000)) {
                    updateOrderStatus(ns, "EXECUTE");
                    changeState(ns, "EXECUTE");
                }
                break;
            case "EXECUTE":
                handleExecute(ns);
                break;
            case "COMPLETING":
                updateTelemetry(ns, "lot_verified", "WAIT_ACK");
                if (timeInState(2000)) {
                    onOrderCompleted(ns);
                }
                break;
            case "COMPLETE":
                break;
            case "RESETTING":
                if (!awaitingMesAck && timeInState(1000)) {
                    resetOrderState(ns);
                    changeState(ns, "IDLE");
                }
                break;
            case "STOPPING":
                updateTelemetry(ns, "alarm_code", "STOP_FI");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }

    private void handleExecute(MultiMachineNameSpace ns) {
        applyOperatingEnergy(ns);
        for (int step = 0; step < SIMULATION_SPEED; step++) {
            double deltaSeconds = 1.0 / SIMULATION_SPEED;
            totalElapsedSeconds += deltaSeconds;
            cycleElapsed += deltaSeconds;

            if (!hasMoreSerials()) {
                if (!"IDLE".equals(state)) {
                    changeState(ns, "IDLE");
                }
                return;
            }
            if (!prepareCurrentSerial(ns)) {
                continue;
            }

            stageElapsed += deltaSeconds;
            while (stageElapsed >= STAGE_DURATIONS_SEC[stageIndex]) {
                stageElapsed -= STAGE_DURATIONS_SEC[stageIndex];
                stageIndex++;
                if (stageIndex >= STAGE_DURATIONS_SEC.length) {
                    concludeSerialCycle(ns);
                    stageIndex = 0;
                    stageElapsed = 0.0;
                    cycleElapsed = 0.0;
                    break;
                }
            }

            updateTelemetry(ns, "t_in_cycle_sec", Math.min(cycleElapsed, TOTAL_CYCLE_TIME_SEC));
        }
    }

    private boolean prepareCurrentSerial(MultiMachineNameSpace ns) {
        if (activeSerial != null && !activeSerial.isEmpty()) {
            return true;
        }
        String nextSerial = acquireNextSerial(ns);
        if (nextSerial.isEmpty()) {
            updateTelemetry(ns, "current_serial", "");
            updateTelemetry(ns, "serial_ok", true);
            updateTelemetry(ns, "ng_type", 0);
            return false;
        }
        currentSerialOkFlag = true;
        currentNgType = 0;
        updateTelemetry(ns, "current_serial", nextSerial);
        updateTelemetry(ns, "serial_ok", true);
        updateTelemetry(ns, "ng_type", 0);
        return true;
    }

    private void concludeSerialCycle(MultiMachineNameSpace ns) {
        sampleProcessMetrics();
        updateMetricTelemetry(ns);

        boolean visionOk = visionScore >= 93.0;
        boolean resistanceOk = electricalResistance >= 2.6 && electricalResistance <= 3.6;

        boolean serialOk = true;
        int ngType = 0;
        if (!visionOk) {
            serialOk = false;
            ngType = NgType.VISION_FAIL;
        } else if (!resistanceOk) {
            serialOk = false;
            ngType = NgType.RESISTANCE_FAIL;
        } else if (!safetyPassed) {
            serialOk = false;
            ngType = NgType.SAFETY_FAIL;
        } else if (!functionPassed) {
            serialOk = false;
            ngType = NgType.FUNCTION_FAIL;
        }

        processedSerialCount++;
        updateProducedQuantity(ns, producedQuantity + 1);

        if (serialOk) {
            completeActiveSerialOk(ns);
            updateQualityCounts(ns, okCount + 1, ngCount);
            currentSerialOkFlag = true;
            currentNgType = 0;
        } else {
            completeActiveSerialNg(ns, ngType);
            updateQualityCounts(ns, okCount, ngCount + 1);
            currentSerialOkFlag = false;
            currentNgType = ngType;
        }

        updateTelemetry(ns, "serial_ok", currentSerialOkFlag);
        updateTelemetry(ns, "ng_type", currentNgType);
        updateProcessCountersTelemetry(ns);

        String nextSerial = acquireNextSerial(ns);
        if (!nextSerial.isEmpty()) {
            updateTelemetry(ns, "current_serial", nextSerial);
            updateTelemetry(ns, "serial_ok", true);
            updateTelemetry(ns, "ng_type", 0);
        } else {
            updateTelemetry(ns, "current_serial", "");
        }
    }

    private void sampleProcessMetrics() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        visionScore = 94.5 + (rnd.nextDouble() - 0.5) * 3.0;
        electricalResistance = 3.1 + (rnd.nextDouble() - 0.5) * 0.6;
        safetyPassed = rnd.nextDouble() > 0.013;
        functionPassed = rnd.nextDouble() > 0.02;
        lotCode = "LOT-" + (2000 + rnd.nextInt(8000));
    }

    private void updateMetricTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "vision_score", visionScore);
        updateTelemetry(ns, "electrical_resistance", electricalResistance);
        updateTelemetry(ns, "safety_passed", safetyPassed);
        updateTelemetry(ns, "function_passed", functionPassed);
        updateTelemetry(ns, "lot_verified", lotCode);
    }

    private void updateProcessCountersTelemetry(MultiMachineNameSpace ns) {
        updateTelemetry(ns, "processed_count", processedSerialCount);
        updateTelemetry(ns, "good_count", okCount);
        updateTelemetry(ns, "ng_count", ngCount);
    }

    @Override
    protected void resetOrderState(MultiMachineNameSpace ns) {
        super.resetOrderState(ns);
        stageIndex = 0;
        stageElapsed = 0.0;
        cycleElapsed = 0.0;
        totalElapsedSeconds = 0.0;
        processedSerialCount = 0;
        currentSerialOkFlag = true;
        currentNgType = 0;
        visionScore = 0.0;
        electricalResistance = 0.0;
        safetyPassed = true;
        functionPassed = true;
        lotCode = "";
        updateTelemetry(ns, "current_serial", "");
        updateTelemetry(ns, "serial_ok", true);
        updateTelemetry(ns, "ng_type", 0);
        updateTelemetry(ns, "processed_count", 0);
        updateTelemetry(ns, "good_count", 0);
        updateTelemetry(ns, "ng_count", 0);
        updateTelemetry(ns, "t_in_cycle_sec", 0.0);
        updateMetricTelemetry(ns);
    }
}
