package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.delegates.AttributeDelegate;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 트레이/시리얼 기반의 병렬 파이프라인 컨트롤러.
 */
public class ProductionLineController {

    private static final int TRAY_CAPACITY = 36;
    private static final int STAGE_TRAY_CLEAN = 1;
    private static final int STAGE_ELECTRODE = 2;

    private final MultiMachineNameSpace namespace;
    private final String lineName;
    private final UaFolderNode lineFolder;
    private final String lineCode;

    private final Map<Integer, StageState> stages = new HashMap<>();
    private final List<Integer> stageOrder = new ArrayList<>();
    private final Map<UnitLogic, MachineAssignment> machineAssignments = new HashMap<>();

    private final Map<UnitLogic, Integer> machineProduction = new HashMap<>();
    private final Map<UnitLogic, Integer> machineOkCounts = new HashMap<>();
    private final Map<UnitLogic, Integer> machineNgCounts = new HashMap<>();
    private final Map<UnitLogic, String> machineStates = new HashMap<>();

    private final Map<String, UaVariableNode> nodes = new HashMap<>();

    private String orderStatus = "IDLE";
    private String orderNo = "";
    private int targetQuantity = 0;
    private int linePpm = 0;
    private boolean awaitingAck = false;
    private boolean orderActive = false;
    private String currentOrderNo = "";
    private int currentOrderTargetQty = 0;
    private int currentOrderPpm = 0;
    private String currentOrderItemCode = "";

    private int finalOkTotal = 0;
    private long trayIdCounter = 0L;
    private long serialCounter = 1L;
    private String serialPrefix = "CC-A";
    private String orderItemCode = "";
    private OffsetDateTime executeStatusStartedAt;
    private OffsetDateTime waitingAckStatusAt;

    public ProductionLineController(MultiMachineNameSpace namespace, String lineName, UaFolderNode lineFolder) {
        this.namespace = namespace;
        this.lineName = lineName;
        this.lineFolder = lineFolder;
        this.lineCode = extractLineCode(lineName);
        initializeNodes();
    }

    private void initializeNodes() {
        nodes.put("order_no", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_no"), orderNo));
        nodes.put("order_item_code", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_item_code"), orderItemCode));
        nodes.put("order_target_qty", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_target_qty"), targetQuantity));
        nodes.put("order_produced_qty", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_produced_qty"), 0));
        nodes.put("order_status", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_status"), orderStatus));
        nodes.put("mes_ack_pending", namespace.addVariableNode(lineFolder, lineQualifiedName(".mes_ack_pending"), awaitingAck));
        nodes.put("order_ppm", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_ppm"), linePpm));
        nodes.put("production_performance_payload",
                namespace.addVariableNode(lineFolder, lineQualifiedName(".production_performance_payload"), ""));

        UaVariableNode commandNode = namespace.addVariableNode(lineFolder, lineQualifiedName(".command"), "");
        commandNode.setDisplayName(LocalizedText.english(lineName + " Command"));
        commandNode.setMinimumSamplingInterval(0.0);
        commandNode.setAccessLevel(org.eclipse.milo.opcua.sdk.core.AccessLevel.toValue(EnumSet.of(
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentRead,
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentWrite)));
        commandNode.setUserAccessLevel(commandNode.getAccessLevel());
        commandNode.setAttributeDelegate(new AttributeDelegate() {
            @Override
            public void setAttribute(AttributeContext context,
                                     org.eclipse.milo.opcua.sdk.core.nodes.Node node,
                                     AttributeId attributeId,
                                     DataValue value) throws UaException {
                AttributeDelegate.super.setAttribute(context, node, attributeId, value);
                if (attributeId == AttributeId.Value) {
                    String raw = value.getValue() != null && value.getValue().getValue() != null
                            ? value.getValue().getValue().toString().trim()
                            : "";
                    if (!raw.isEmpty()) {
                        handleCommand(raw);
                    }
                }
            }
        });
        nodes.put("command", commandNode);
    }

    private static String extractLineCode(String fullName) {
        int dotIdx = fullName.lastIndexOf('.');
        if (dotIdx >= 0 && dotIdx < fullName.length() - 1) {
            return fullName.substring(dotIdx + 1);
        }
        return fullName;
    }

    private String lineQualifiedName(String suffix) {
        return lineName + suffix;
    }

    void registerMachine(UnitLogic machine) {
        StageState state = stages.computeIfAbsent(machine.getMachineNo(), StageState::new);
        state.machines.add(machine);
        if (!stageOrder.contains(state.stageNo)) {
            stageOrder.add(state.stageNo);
            stageOrder.sort(Integer::compareTo);
        }
        machineProduction.put(machine, 0);
        machineOkCounts.put(machine, 0);
        machineNgCounts.put(machine, 0);
        machineStates.put(machine, machine.state);
        machineAssignments.remove(machine);
        machine.setLineController(this);
    }

    private synchronized void handleCommand(String command) {
        String[] tokens = command.split(":");
        String action = tokens[0].trim();
        switch (action.toUpperCase()) {
            case "START":
                if (tokens.length < 3) {
                    System.err.printf("[%s] START requires START:<order>:<qty>[:<itemPrefix>]\n", lineName);
                    return;
                }
                try {
                    String orderId = tokens[1];
                    Integer targetQty = Integer.parseInt(tokens[2]);
                    String itemPrefix = tokens.length >= 4 ? tokens[3] : serialPrefix;
                    processMesCommand(action, orderId, targetQty, itemPrefix);
                } catch (NumberFormatException ex) {
                    System.err.printf("[%s] Invalid START parameters '%s': %s\n", lineName, command, ex.getMessage());
                }
                break;
            case "ACK":
            case "STOP":
                processMesCommand(action, null, null, null);
                break;
            default:
                System.err.printf("[%s] Unsupported line command '%s'%n", lineName, command);
        }
    }

    public synchronized void processMesCommand(String action,
                                               String orderId,
                                               Integer targetQty,
                                               String itemCode) {
        if (action == null || action.isBlank()) {
            System.err.printf("[%s] Missing action for MES command.%n", lineName);
            return;
        }

        String normalizedAction = action.trim().toUpperCase();
        switch (normalizedAction) {
            case "START":
                if (orderId == null || orderId.isBlank()) {
                    System.err.printf("[%s] START command requires orderNo.%n", lineName);
                    return;
                }
                if (targetQty == null || targetQty <= 0) {
                    System.err.printf("[%s] START command requires positive targetQty but got %s.%n",
                            lineName, String.valueOf(targetQty));
                    return;
                }
                String sanitizedItemCode = itemCode != null && !itemCode.isBlank() ? itemCode.trim() : null;
                startOrder(orderId.trim(), targetQty, sanitizedItemCode);
                break;
            case "STOP":
                stopLine();
                break;
            case "ACK":
                acknowledge();
                break;
            default:
                System.err.printf("[%s] Unsupported MES action '%s'%n", lineName, normalizedAction);
        }
    }

    private synchronized void startOrder(String orderId, int targetQty, String itemCode) {
        if (orderActive || awaitingAck) {
            System.err.printf("[%s] Line busy; cannot start new order.%n", lineName);
            return;
        }
        this.orderActive = true;
        this.awaitingAck = false;
        this.orderNo = orderId;
        this.targetQuantity = targetQty;
        String sanitizedItemCode = itemCode != null ? itemCode.trim() : "";
        if (!sanitizedItemCode.isEmpty()) {
            this.serialPrefix = sanitizedItemCode;
        }
        this.orderItemCode = sanitizedItemCode;
        this.finalOkTotal = 0;
        this.currentOrderNo = orderId;
        this.currentOrderTargetQty = targetQty;
        this.currentOrderPpm = 0;
        this.currentOrderItemCode = sanitizedItemCode;
        this.trayIdCounter = 0;

        machineAssignments.clear();
        machineProduction.replaceAll((m, v) -> 0);
        machineOkCounts.replaceAll((m, v) -> 0);
        machineNgCounts.replaceAll((m, v) -> 0);
        machineStates.replaceAll((m, v) -> "IDLE");
        stages.values().forEach(StageState::clearQueue);

        orderStatus = "PREPARING";
        updateLineTelemetry();
        machines().forEach(machine -> machine.updateOrderItemCode(namespace, sanitizedItemCode));
        updateNode("order_produced_qty", 0);
        updateNode("production_performance_payload", "");

        StageState firstStage = stages.get(STAGE_TRAY_CLEAN);
        if (firstStage == null) {
            System.err.printf("[%s] No TrayClean stage registered.%n", lineName);
            return;
        }
        int traysNeeded = Math.max(1, (int) Math.ceil((double) targetQty / TRAY_CAPACITY));
        for (int i = 0; i < traysNeeded; i++) {
            firstStage.queue.addLast(new Tray(nextTrayId(), TRAY_CAPACITY));
        }
        dispatchStage(STAGE_TRAY_CLEAN);
        ensureUpstreamSupply();
        orderStatus = "EXECUTE";
        executeStatusStartedAt = OffsetDateTime.now();
        waitingAckStatusAt = null;
        updateLineTelemetry();
    }

    private synchronized void acknowledge() {
        if (!awaitingAck) {
            System.err.printf("[%s] No pending ACK.%n", lineName);
            return;
        }
        for (UnitLogic machine : machines()) {
            machine.endContinuousOrder();
            machine.acknowledgeOrderCompletion(namespace);
            machine.resetOrderState(namespace);
        }
        awaitingAck = false;
        orderActive = false;
        orderStatus = "ACKED";
        orderItemCode = "";
        currentOrderItemCode = "";
        updateLineTelemetry();
    }

    private synchronized void stopLine() {
        machines().forEach(UnitLogic::requestSimulationStop);
        machineAssignments.clear();
        stages.values().forEach(StageState::clearQueue);
        orderStatus = "STOPPING";
        orderActive = false;
        awaitingAck = false;
        updateLineTelemetry();
    }

    public synchronized void onMachineProduced(UnitLogic machine, int producedQty, int targetQty) {
        machineProduction.put(machine, producedQty);
        checkTrayCompletion(machine);
    }

    public synchronized void onMachineQualityChanged(UnitLogic machine, int okTotal, int ngTotal) {
        machineOkCounts.put(machine, okTotal);
        machineNgCounts.put(machine, ngTotal);
        checkTrayCompletion(machine);
    }

    public synchronized void onMachineAckPendingChanged(UnitLogic machine, boolean pending) {
        machineStates.put(machine, pending ? "WAIT_ACK" : machine.state);
    }

    public synchronized void onMachineStateChanged(UnitLogic machine, String newState) {
        machineStates.put(machine, newState);
        if (!orderActive) return;
        if ("IDLE".equalsIgnoreCase(newState)) {
            MachineAssignment assignment = machineAssignments.get(machine);
            if (assignment != null) {
                completeTray(machine);
            }
            StageState state = stages.get(machine.getMachineNo());
            if (state != null) {
                dispatchStage(state.stageNo);
            }
        }
    }

    public synchronized void onMachineReset(UnitLogic machine) {
        machineAssignments.remove(machine);
        machineProduction.put(machine, 0);
        machineOkCounts.put(machine, 0);
        machineNgCounts.put(machine, 0);
        machineStates.put(machine, machine.state);
    }

    private void checkTrayCompletion(UnitLogic machine) {
        MachineAssignment assignment = machineAssignments.get(machine);
        if (assignment == null) return;
        int producedDelta = machineProduction.getOrDefault(machine, 0) - assignment.startProduced;
        int okDelta = machineOkCounts.getOrDefault(machine, 0) - assignment.startOk;
        int ngDelta = machineNgCounts.getOrDefault(machine, 0) - assignment.startNg;
        if (producedDelta >= assignment.plannedQty || okDelta + ngDelta >= assignment.plannedQty) {
            completeTray(machine);
        }
    }

    private void completeTray(UnitLogic machine) {
        MachineAssignment assignment = machineAssignments.remove(machine);
        if (assignment == null) return;

        StageState stage = stages.get(assignment.stageNo);
        Tray tray = assignment.tray;

        int totalOk = machineOkCounts.getOrDefault(machine, 0);
        int totalNg = machineNgCounts.getOrDefault(machine, 0);
        int okDelta = Math.max(0, totalOk - assignment.startOk);
        int ngDelta = Math.max(0, totalNg - assignment.startNg);

        tray.serials.clear();
        tray.serials.addAll(machine.getTrayCompletedOkSerialsSnapshot());
        tray.rejectedSerials.clear();
        tray.rejectedSerials.addAll(machine.getTrayRejectedSerialsSnapshot());

        if (stage.stageNo == STAGE_TRAY_CLEAN && tray.serials.isEmpty()) {
            int serialCount = Math.max(0, assignment.plannedQty - ngDelta);
            tray.serials.addAll(generateSerials(serialCount));
        }
        tray.plannedQty = tray.serials.size();
        System.out.printf(
                "[LineController] Stage %d completed by %s | tray=%s planned=%d serials=%d okDelta=%d ngDelta=%d%n",
                stage.stageNo,
                machine.getName(),
                tray.trayId,
                tray.plannedQty,
                tray.serials.size(),
                okDelta,
                ngDelta
        );

        int stageIndex = stageOrder.indexOf(stage.stageNo);
        if (stageIndex >= 0 && stageIndex < stageOrder.size() - 1) {
            int nextStageNo = stageOrder.get(stageIndex + 1);
            StageState next = stages.get(nextStageNo);
            if (next != null && tray.plannedQty > 0) {
                System.out.printf("[LineController] Dispatch tray %s to stage %d queue size(before)=%d%n",
                        tray.trayId,
                        nextStageNo,
                        next.queue.size());
                next.queue.addLast(tray);
                dispatchStage(nextStageNo);
            } else if (next != null) {
                System.out.printf("[LineController] Tray %s has no OK serials; skipping stage %d%n",
                        tray.trayId,
                        nextStageNo);
            }
        } else {
            finalOkTotal += okDelta;
            updateNode("order_produced_qty", finalOkTotal);
        }

        boolean lineDone = finalOkTotal >= targetQuantity;
        if (lineDone && allTraysCompleted()) {
            awaitingAck = true;
            orderStatus = "WAITING_ACK";
            waitingAckStatusAt = OffsetDateTime.now();
            updateLineTelemetry();
            publishProductionPerformancePayload();
        } else {
            dispatchStage(stage.stageNo);
            ensureUpstreamSupply();
        }
    }

    private boolean allTraysCompleted() {
        if (!machineAssignments.isEmpty()) {
            return false;
        }
        for (StageState state : stages.values()) {
            if (!state.queue.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void ensureUpstreamSupply() {
        if (!orderActive || finalOkTotal >= targetQuantity) {
            return;
        }
        int potential = calculateOutstandingPotential();
        if (finalOkTotal + potential >= targetQuantity) {
            return;
        }
        StageState firstStage = stages.get(STAGE_TRAY_CLEAN);
        if (firstStage != null) {
            firstStage.queue.addLast(new Tray(nextTrayId(), TRAY_CAPACITY));
            dispatchStage(STAGE_TRAY_CLEAN);
        }
    }

    private int calculateOutstandingPotential() {
        int potential = 0;
        for (StageState stage : stages.values()) {
            for (Tray tray : stage.queue) {
                potential += Math.max(0, tray.plannedQty);
            }
        }
        for (MachineAssignment assignment : machineAssignments.values()) {
            int producedDelta = machineProduction.getOrDefault(assignment.machine, 0) - assignment.startProduced;
            potential += Math.max(0, assignment.plannedQty - producedDelta);
        }
        return potential;
    }

    private void dispatchStage(int stageNo) {
        StageState stage = stages.get(stageNo);
        if (stage == null) return;
        while (!stage.queue.isEmpty()) {
            boolean anyAssigned = false;
            System.out.printf("[LineController] Dispatch stage %d queue size=%d%n", stageNo, stage.queue.size());
            for (UnitLogic machine : stage.machines) {
                if (stage.queue.isEmpty()) break;
                if (machineAssignments.containsKey(machine)) continue;
                if (machine.awaitingMesAck) continue;
                String stateName = machineStates.getOrDefault(machine, machine.state);
                System.out.printf("[LineController]  machine=%s state=%s awaitingAck=%s%n",
                        machine.getName(),
                        stateName,
                        machine.awaitingMesAck);
                if (!"IDLE".equalsIgnoreCase(stateName)) continue;

                Tray tray = stage.queue.pollFirst();
                if (tray == null) break;
                assignTrayToMachine(stage.stageNo, machine, tray);
                anyAssigned = true;
            }
            if (!anyAssigned) {
                break;
            }
        }
    }

    private void assignTrayToMachine(int stageNo, UnitLogic machine, Tray tray) {
        if (stageNo == STAGE_TRAY_CLEAN && tray.serials.isEmpty()) {
            tray.serials.addAll(generateSerials(tray.plannedQty));
        }
        System.out.printf("[LineController] Assign tray %s (serials=%d) to %s stage=%d%n",
                tray.trayId,
                tray.serials.size(),
                machine.getName(),
                stageNo);
        machine.assignTray(namespace, tray.trayId, tray.serials);
        if (!machine.isContinuousMode()) {
            machine.beginContinuousOrder(namespace, currentOrderNo, tray.plannedQty, machine.getDefaultPpm(), currentOrderItemCode);
        } else {
            machine.appendOrderTarget(namespace, tray.plannedQty);
            machine.updateOrderItemCode(namespace, currentOrderItemCode);
        }
        MachineAssignment assignment = new MachineAssignment(stageNo, machine, tray,
                machineProduction.getOrDefault(machine, 0),
                machineOkCounts.getOrDefault(machine, 0),
                machineNgCounts.getOrDefault(machine, 0));
        machineAssignments.put(machine, assignment);
    }

    private List<String> generateSerials(int count) {
        List<String> serials = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            serials.add(serialPrefix + String.format("%020d", serialCounter++));
        }
        return serials;
    }

    private String nextTrayId() {
        trayIdCounter++;
        return String.format("synklabTID-%010d", trayIdCounter);
    }

    private Iterable<UnitLogic> machines() {
        List<UnitLogic> list = new ArrayList<>();
        for (StageState state : stages.values()) {
            list.addAll(state.machines);
        }
        return list;
    }

    private void updateLineTelemetry() {
        updateNode("order_no", orderNo);
        updateNode("order_item_code", orderItemCode);
        updateNode("order_target_qty", targetQuantity);
        updateNode("order_ppm", linePpm);
        updateNode("order_status", orderStatus);
        updateNode("mes_ack_pending", awaitingAck);
    }

    private void publishProductionPerformancePayload() {
        String execTime = executeStatusStartedAt == null ? "" : executeStatusStartedAt.toString();
        String waitingTime = waitingAckStatusAt == null ? "" : waitingAckStatusAt.toString();
        int totalNg = calculateTotalNgCount();
        String payload = String.format(
                "{\"order_no\":\"%s\",\"line_code\":\"%s\",\"item_code\":\"%s\",\"order_produced_qty\":%d,\"order_ng_qty\":%d,\"execute_at\":\"%s\",\"waiting_ack_at\":\"%s\"}",
                orderNo == null ? "" : orderNo,
                lineCode,
                orderItemCode == null ? "" : orderItemCode,
                finalOkTotal,
                totalNg,
                execTime,
                waitingTime
        );
        updateNode("production_performance_payload", payload);
    }

    private int calculateTotalNgCount() {
        int total = 0;
        for (Integer value : machineNgCounts.values()) {
            if (value != null) {
                total += value;
            }
        }
        return total;
    }

    private void updateNode(String key, Object value) {
        UaVariableNode node = nodes.get(key);
        if (node != null) {
            namespace.updateValue(node, value);
        }
    }

    private static final class StageState {
        final int stageNo;
        final List<UnitLogic> machines = new ArrayList<>();
        final Deque<Tray> queue = new ArrayDeque<>();

        StageState(int stageNo) {
            this.stageNo = stageNo;
        }

        void clearQueue() {
            queue.clear();
        }
    }

    private static final class Tray {
        final String trayId;
        int plannedQty;
        final List<String> serials = new ArrayList<>();
        final List<String> rejectedSerials = new ArrayList<>();

        Tray(String trayId, int plannedQty) {
            this.trayId = trayId;
            this.plannedQty = plannedQty;
        }
    }

    private static final class MachineAssignment {
        final int stageNo;
        final UnitLogic machine;
        final Tray tray;
        final int plannedQty;
        final int startProduced;
        final int startOk;
        final int startNg;

        MachineAssignment(int stageNo,
                          UnitLogic machine,
                          Tray tray,
                          int startProduced,
                          int startOk,
                          int startNg) {
            this.stageNo = stageNo;
            this.machine = machine;
            this.tray = tray;
            this.plannedQty = tray.plannedQty;
            this.startProduced = startProduced;
            this.startOk = startOk;
            this.startNg = startNg;
        }
    }
}
