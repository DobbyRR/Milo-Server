package com.synclab.miloserver.opcua;

import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.delegates.AttributeDelegate;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProductionLineController {

    private final MultiMachineNameSpace namespace;
    private final String lineName;
    private final UaFolderNode lineFolder;
    private final List<UnitLogic> machines = new ArrayList<>();
    private final Map<UnitLogic, Integer> machineProduction = new HashMap<>();
    private final Map<UnitLogic, String> machineStates = new HashMap<>();
    private final Map<UnitLogic, Boolean> machineStarted = new HashMap<>();
    private final Map<UnitLogic, Boolean> machineCompleted = new HashMap<>();

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

    public ProductionLineController(MultiMachineNameSpace namespace, String lineName, UaFolderNode lineFolder) {
        this.namespace = namespace;
        this.lineName = lineName;
        this.lineFolder = lineFolder;

        initializeNodes();
    }

    private void initializeNodes() {
        nodes.put("order_no", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_no"), orderNo));
        nodes.put("order_target_qty", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_target_qty"), targetQuantity));
        nodes.put("order_produced_qty", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_produced_qty"), 0));
        nodes.put("order_status", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_status"), orderStatus));
        nodes.put("mes_ack_pending", namespace.addVariableNode(lineFolder, lineQualifiedName(".mes_ack_pending"), awaitingAck));
        nodes.put("order_ppm", namespace.addVariableNode(lineFolder, lineQualifiedName(".order_ppm"), linePpm));

        UaVariableNode commandNode = buildCommandNode();
        nodes.put("command", commandNode);
    }

    private UaVariableNode buildCommandNode() {
        UaVariableNode node = namespace.addVariableNode(lineFolder, lineQualifiedName(".command"), "");
        node.setDisplayName(LocalizedText.english(lineName + " Command"));
        node.setMinimumSamplingInterval(0.0);
        node.setAccessLevel(org.eclipse.milo.opcua.sdk.core.AccessLevel.toValue(EnumSet.of(
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentRead,
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentWrite)));
        node.setUserAccessLevel(org.eclipse.milo.opcua.sdk.core.AccessLevel.toValue(EnumSet.of(
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentRead,
                org.eclipse.milo.opcua.sdk.core.AccessLevel.CurrentWrite)));

        node.setAttributeDelegate(new AttributeDelegate() {
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

        return node;
    }

    private String lineQualifiedName(String suffix) {
        return lineName + suffix;
    }

    void registerMachine(UnitLogic machine) {
        machines.add(machine);
        machineProduction.put(machine, 0);
        machineStates.put(machine, machine.state);
        machineStarted.put(machine, false);
        machineCompleted.put(machine, false);
        machine.setLineController(this);
    }

    private synchronized void handleCommand(String command) {
        String[] tokens = command.split(":");
        String action = tokens[0].trim().toUpperCase();

        switch (action) {
            case "START":
                if (tokens.length < 4) {
                    System.err.printf("[%s] START requires lineCommand START:<order>:<qty>:<ppm>%n", lineName);
                    return;
                }
                try {
                    String orderId = tokens[1];
                    int targetQty = Integer.parseInt(tokens[2]);
                    int ppm = Integer.parseInt(tokens[3]);
                    startOrder(orderId, targetQty, ppm);
                } catch (NumberFormatException ex) {
                    System.err.printf("[%s] Invalid START parameters '%s': %s%n", lineName, command, ex.getMessage());
                }
                break;
            case "ACK":
                acknowledge();
                break;
            case "STOP":
                stopLine();
                break;
            default:
                System.err.printf("[%s] Unsupported line command '%s'%n", lineName, command);
        }
    }

    private synchronized void startOrder(String orderId, int targetQty, int ppm) {
        if (orderActive || awaitingAck) {
            System.err.printf("[%s] Line busy; cannot start new order.%n", lineName);
            return;
        }

        int effectiveLinePpm = ppm > 0
                ? ppm
                : (!machines.isEmpty() ? machines.get(0).getDefaultPpm() : 0);

        this.orderActive = true;
        this.awaitingAck = false;
        this.orderNo = orderId;
        this.targetQuantity = targetQty;
        this.linePpm = effectiveLinePpm;
        this.orderStatus = "RUNNING";
        this.currentOrderNo = orderId;
        this.currentOrderTargetQty = targetQty;
        this.currentOrderPpm = ppm;

        machineProduction.replaceAll((m, v) -> 0);
        machineStates.replaceAll((m, v) -> "IDLE");
        machineStarted.replaceAll((m, v) -> false);
        machineCompleted.replaceAll((m, v) -> false);

        updateLineTelemetry();
        updateNode("order_produced_qty", 0);

        startMachineAtIndex(0);
    }

    private synchronized void acknowledge() {
        if (!awaitingAck) {
            System.err.printf("[%s] No machines awaiting MES ACK.%n", lineName);
            return;
        }
        machines.stream()
                .filter(machine -> machine.awaitingMesAck)
                .forEach(machine -> machine.acknowledgeOrderCompletion(namespace));
        this.orderStatus = "ACKED";
        this.awaitingAck = false;
        this.orderActive = false;
        updateLineTelemetry();
    }

    private synchronized void stopLine() {
        machines.forEach(UnitLogic::requestSimulationStop);
        this.orderStatus = "STOPPING";
        this.orderActive = false;
        updateLineTelemetry();
    }

    private void updateLineTelemetry() {
        updateNode("order_no", orderNo);
        updateNode("order_target_qty", targetQuantity);
        updateNode("order_ppm", linePpm);
        updateNode("order_status", orderStatus);
        updateNode("mes_ack_pending", awaitingAck);
    }

    private void updateNode(String key, Object value) {
        UaVariableNode node = nodes.get(key);
        if (node != null) {
            namespace.updateValue(node, value);
        }
    }

    public synchronized void onMachineProduced(UnitLogic machine, int producedQty, int targetQty) {
        machineProduction.put(machine, producedQty);
        maybeStartNextMachine(machine, producedQty);

        UnitLogic lastMachine = machines.isEmpty() ? null : machines.get(machines.size() - 1);
        if (lastMachine != null) {
            int finishedOutput = machineProduction.getOrDefault(lastMachine, 0);
            updateNode("order_produced_qty", finishedOutput);
            if (orderActive && targetQuantity > 0 && finishedOutput >= targetQuantity) {
                this.awaitingAck = true;
                this.orderStatus = "WAITING_ACK";
                updateLineTelemetry();
            }
        }

        if (targetQty > 0 && producedQty >= targetQty) {
            machineCompleted.put(machine, true);
        }
    }

    public synchronized void onMachineAckPendingChanged(UnitLogic machine, boolean pending) {
        if (pending) {
            if (isLastMachine(machine)) {
                this.awaitingAck = true;
                this.orderStatus = "WAITING_ACK";
                updateLineTelemetry();
            } else {
                machine.acknowledgeOrderCompletion(namespace);
            }
        } else if (machines.stream().noneMatch(m -> m.awaitingMesAck)) {
            this.awaitingAck = false;
            updateLineTelemetry();
        }
    }

    public synchronized void onMachineStateChanged(UnitLogic machine, String newState) {
        machineStates.put(machine, newState);
        if (!orderActive && machines.stream().allMatch(m -> "IDLE".equalsIgnoreCase(machineStates.get(m)))) {
            resetLineState();
        }
    }

    public synchronized void onMachineReset(UnitLogic machine) {
        machineProduction.put(machine, 0);
        machineStarted.put(machine, false);
        machineCompleted.put(machine, false);
        if (machines.stream().allMatch(m -> machineProduction.get(m) == 0)) {
            updateNode("order_produced_qty", 0);
        }
    }

    private void startMachineAtIndex(int index) {
        if (index < 0 || index >= machines.size()) {
            return;
        }
        UnitLogic machine = machines.get(index);
        if (Boolean.TRUE.equals(machineStarted.get(machine))) {
            return;
        }
        machineStarted.put(machine, true);
        machineCompleted.put(machine, false);
        machineProduction.put(machine, 0);
        try {
            machine.startOrder(namespace, currentOrderNo, currentOrderTargetQty, currentOrderPpm);
        } catch (Exception ex) {
            System.err.printf("[%s] Failed to start machine %s: %s%n",
                    lineName, machine.getName(), ex.getMessage());
        }
    }

    private void maybeStartNextMachine(UnitLogic machine, int producedQty) {
        if (!orderActive || producedQty <= 0) {
            return;
        }
        int index = machines.indexOf(machine);
        if (index == -1) {
            return;
        }
        int nextIndex = index + 1;
        if (nextIndex < machines.size()) {
            UnitLogic next = machines.get(nextIndex);
            if (!Boolean.TRUE.equals(machineStarted.get(next))) {
                startMachineAtIndex(nextIndex);
            }
        }
    }

    private boolean isLastMachine(UnitLogic machine) {
        return !machines.isEmpty() && machines.get(machines.size() - 1) == machine;
    }

    private void resetLineState() {
        this.orderActive = false;
        this.awaitingAck = false;
        this.orderNo = "";
        this.targetQuantity = 0;
        this.linePpm = 0;
        this.currentOrderNo = "";
        this.currentOrderTargetQty = 0;
        this.currentOrderPpm = 0;
        this.orderStatus = "IDLE";
        machineProduction.replaceAll((m, v) -> 0);
        machineStarted.replaceAll((m, v) -> false);
        machineCompleted.replaceAll((m, v) -> false);
        updateLineTelemetry();
        updateNode("order_produced_qty", 0);
    }
}
