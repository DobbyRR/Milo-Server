package com.synclab.miloserver.machine.mainFactory.cylindricalLine.trayCleanUnit1st;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import com.synclab.miloserver.opcua.UnitLogic;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

import java.util.concurrent.ThreadLocalRandom;

public class TrayCleaner02 extends UnitLogic {

    private static final double CLEANING_DURATION_SEC = 7.5;

    private double idlePressurePhase = Math.PI / 3;
    private boolean cleaningActive = false;
    private double cleaningElapsed = 0.0;
    private int trayPlannedSlots = 0;
    private boolean trayResultOk = true;
    private int trayNgType = 0;

    private double surfaceCleanliness = 0.0;
    private double staticLevel = 0.0;
    private double airPressureBar = 0.0;
    private double transferSpeedMps = 0.0;
    private double transferTimeSec = 0.0;

    /**
     * TrayCleaner NG Type codes (1~4)
     * 1 - 세정 부족(표면 오염 잔류)
     * 2 - 정전기 과다
     * 3 - 공압 부족
     * 4 - 트레이 손상/파손
     */
    private static final String[] NG_TYPE_NAMES = {
            "세정 부족(표면 오염 잔류)",
            "정전기 과다",
            "공압 부족",
            "트레이 손상/파손"
    };
    private static final class NgType {
        static final int INSUFFICIENT_CLEANING = 1;
        static final int EXCESS_STATIC = 2;
        static final int LOW_AIR_PRESSURE = 3;
        static final int TRAY_DAMAGE = 4;

        private NgType() {}
    }

    public TrayCleaner02(String name, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, folder);
        this.unitType = "TRAY_CLEAN";
        this.lineId = "CylindricalLine";
        this.machineNo = 1;
        this.equipmentId = "TC-02";
        this.processId = "DryClean";
        setUnitsPerCycle(36);
        setDefaultPpm(66);
        configureEnergyProfile(0.15, 0.03, 2.8, 0.35);

        setupCommonTelemetry(ns);
        registerNgTypeNames(NG_TYPE_NAMES);
        setupVariables(ns);
    }

    @Override
    public void setupVariables(MultiMachineNameSpace ns) {
        telemetryNodes.put("conveyor_id", ns.addVariableNode(machineFolder, name + ".conveyor_id", "CV_01_BRANCH_B"));
        telemetryNodes.put("source_unit", ns.addVariableNode(machineFolder, name + ".source_unit", "LOAD_UNIT"));
        telemetryNodes.put("target_unit", ns.addVariableNode(machineFolder, name + ".target_unit", "ELECTRODE_UNIT"));
        telemetryNodes.put("direction", ns.addVariableNode(machineFolder, name + ".direction", "FWD"));
        telemetryNodes.put("branch_state", ns.addVariableNode(machineFolder, name + ".branch_state", "RIGHT"));
        telemetryNodes.put("occupied", ns.addVariableNode(machineFolder, name + ".occupied", false));
        telemetryNodes.put("tray_id", ns.addVariableNode(machineFolder, name + ".tray_id", ""));
        telemetryNodes.put("speed", ns.addVariableNode(machineFolder, name + ".speed", 0.0));
        telemetryNodes.put("jam_alarm", ns.addVariableNode(machineFolder, name + ".jam_alarm", false));
        telemetryNodes.put("sensor_status", ns.addVariableNode(machineFolder, name + ".sensor_status", "{}"));
        telemetryNodes.put("transfer_time", ns.addVariableNode(machineFolder, name + ".transfer_time", 0.0));
        telemetryNodes.put("surface_cleanliness", ns.addVariableNode(machineFolder, name + ".surface_cleanliness", 0.0));
        telemetryNodes.put("static_level", ns.addVariableNode(machineFolder, name + ".static_level", 0.0));
        telemetryNodes.put("air_pressure", ns.addVariableNode(machineFolder, name + ".air_pressure", 0.0));
        telemetryNodes.put("tray_tag_valid", ns.addVariableNode(machineFolder, name + ".tray_tag_valid", false));
    }

    @Override
    public void onCommand(MultiMachineNameSpace ns, String command) {
        if (!handleCommonCommand(ns, command)) {
            System.err.printf("[TrayCleaner02] Unsupported command '%s'%n", command);
        }
    }

    @Override
    public synchronized void assignTray(MultiMachineNameSpace ns, String newTrayId, java.util.List<String> okSerials) {
        super.assignTray(ns, newTrayId, okSerials);
        trayPlannedSlots = getUnitsPerCycle();
        cleaningActive = false;
        cleaningElapsed = 0.0;
        trayResultOk = true;
        trayNgType = 0;
        updateTelemetry(ns, "occupied", true);
        updateTelemetry(ns, "tray_tag_valid", true);
    }

    @Override
    public void simulateStep(MultiMachineNameSpace ns) {
        switch (state) {
            case "IDLE":
                simulateIdle(ns);
                break;
            case "STARTING":
                if (timeInState(1500)) {
                    updateOrderStatus(ns, "EXECUTE");
                    changeState(ns, "EXECUTE");
                }
                break;
            case "EXECUTE":
                handleExecute(ns);
                break;
            case "COMPLETING":
                if (timeInState(1500)) {
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
                updateTelemetry(ns, "alarm_code", "STOP_TC");
                updateTelemetry(ns, "alarm_level", "INFO");
                if (timeInState(1000)) {
                    changeState(ns, "IDLE");
                }
                break;
        }
    }

    private void simulateIdle(MultiMachineNameSpace ns) {
        idlePressurePhase += 0.22;
        if (idlePressurePhase > Math.PI * 2) {
            idlePressurePhase -= Math.PI * 2;
        }
        double idlePressure = 5.1 + Math.sin(idlePressurePhase) * 0.3 + (Math.random() - 0.5) * 0.06;
        double idleStatic = 0.024 + Math.abs(Math.sin(idlePressurePhase / 2)) * 0.02;
        updateTelemetry(ns, "air_pressure", idlePressure);
        updateTelemetry(ns, "static_level", idleStatic);
        updateTelemetry(ns, "occupied", false);
        updateTelemetry(ns, "tray_tag_valid", false);
        applyIdleDrift(ns);
    }

    private void handleExecute(MultiMachineNameSpace ns) {
        applyOperatingEnergy(ns);
        if (trayPlannedSlots <= 0) {
            if (!"IDLE".equals(state)) {
                changeState(ns, "IDLE");
            }
            return;
        }
        if (!cleaningActive) {
            startCleaningCycle(ns);
            return;
        }
        cleaningElapsed += 1.0;
        transferSpeedMps = 0.24 + (Math.random() - 0.5) * 0.07;
        transferTimeSec = CLEANING_DURATION_SEC + (Math.random() - 0.5);
        updateTelemetry(ns, "speed", transferSpeedMps);
        updateTelemetry(ns, "transfer_time", transferTimeSec);

        if (cleaningElapsed >= CLEANING_DURATION_SEC) {
            concludeCleaning(ns);
        }
    }

    private void startCleaningCycle(MultiMachineNameSpace ns) {
        cleaningActive = true;
        cleaningElapsed = 0.0;
        sampleCleaningMetrics();
        updateTelemetry(ns, "surface_cleanliness", surfaceCleanliness);
        updateTelemetry(ns, "static_level", staticLevel);
        updateTelemetry(ns, "air_pressure", airPressureBar);
        updateTelemetry(ns, "speed", transferSpeedMps);
        updateTelemetry(ns, "transfer_time", transferTimeSec);
    }

    private void concludeCleaning(MultiMachineNameSpace ns) {
        cleaningActive = false;
        evaluateTrayResult();

        int okIncrement = trayResultOk ? trayPlannedSlots : 0;
        int ngIncrement = trayResultOk ? 0 : trayPlannedSlots;
        updateProducedQuantity(ns, producedQuantity + trayPlannedSlots);
        updateQualityCounts(ns, okCount + okIncrement, ngCount + ngIncrement);

        if (!trayResultOk && trayNgType > 0) {
            trayNgTypeCounts[trayNgType - 1] += trayPlannedSlots;
            lastNgType = trayNgType;
            updateNgTelemetry(ns);
            updateTelemetry(ns, "jam_alarm", trayNgType == NgType.TRAY_DAMAGE);
        } else {
            updateTelemetry(ns, "jam_alarm", false);
        }

        trayPlannedSlots = 0;
        updateTelemetry(ns, "occupied", false);
        updateTelemetry(ns, "tray_tag_valid", false);
        changeState(ns, "IDLE");
    }

    private void sampleCleaningMetrics() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        surfaceCleanliness = 91.5 + (rnd.nextDouble() - 0.5) * 5.5;
        staticLevel = 0.023 + Math.abs(rnd.nextGaussian()) * 0.022;
        airPressureBar = 5.7 + (rnd.nextDouble() - 0.5) * 0.55;
        transferSpeedMps = 0.24 + (rnd.nextDouble() - 0.5) * 0.08;
        transferTimeSec = CLEANING_DURATION_SEC + (rnd.nextDouble() - 0.5);
    }

    private void evaluateTrayResult() {
        trayResultOk = true;
        trayNgType = 0;

        boolean cleanlinessOk = surfaceCleanliness >= 87.0;
        boolean staticOk = staticLevel <= 0.055;
        boolean airPressureOk = airPressureBar >= 5.2 && airPressureBar <= 6.9;
        boolean trayDamage = ThreadLocalRandom.current().nextDouble() > 0.994;

        if (!cleanlinessOk) {
            trayResultOk = false;
            trayNgType = NgType.INSUFFICIENT_CLEANING;
        } else if (!staticOk) {
            trayResultOk = false;
            trayNgType = NgType.EXCESS_STATIC;
        } else if (!airPressureOk) {
            trayResultOk = false;
            trayNgType = NgType.LOW_AIR_PRESSURE;
        } else if (trayDamage) {
            trayResultOk = false;
            trayNgType = NgType.TRAY_DAMAGE;
        }
    }

    @Override
    protected void resetOrderState(MultiMachineNameSpace ns) {
        super.resetOrderState(ns);
        cleaningActive = false;
        cleaningElapsed = 0.0;
        trayPlannedSlots = 0;
        trayResultOk = true;
        trayNgType = 0;
        updateTelemetry(ns, "occupied", false);
        updateTelemetry(ns, "tray_tag_valid", false);
        updateTelemetry(ns, "speed", 0.0);
        updateTelemetry(ns, "transfer_time", 0.0);
        updateTelemetry(ns, "surface_cleanliness", 0.0);
        updateTelemetry(ns, "static_level", 0.0);
        updateTelemetry(ns, "air_pressure", 0.0);
    }
}
