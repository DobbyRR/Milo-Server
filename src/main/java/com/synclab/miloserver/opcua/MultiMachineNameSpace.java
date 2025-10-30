package com.synclab.miloserver.opcua;

import com.synclab.miloserver.machine.cylindricalLine.assemblyUnit3rd.AssemblyUnit01;
import com.synclab.miloserver.machine.cylindricalLine.cellCleanUnit6th.CellCleaner01;
import com.synclab.miloserver.machine.cylindricalLine.electrodeUnit2nd.ElectrodeUnit01;
import com.synclab.miloserver.machine.cylindricalLine.finalInspection.FinalInspection01;
import com.synclab.miloserver.machine.cylindricalLine.formationUnit4th.FormationUnit01;
import com.synclab.miloserver.machine.cylindricalLine.moduleAndPackUnit5th.ModulAndPackUnit01;
import com.synclab.miloserver.machine.cylindricalLine.trayCleanUnit1st.TrayCleaner01;
import com.synclab.miloserver.machine.cylindricalLine.trayCleanUnit1st.TrayCleaner02;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.core.ValueRanks;
import org.eclipse.milo.opcua.sdk.core.nodes.VariableNode;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.api.DataItem;
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.AttributeContext;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.delegates.AttributeDelegate;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.QualifiedName;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;


import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MultiMachineNameSpace extends ManagedNamespaceWithLifecycle {

    private static MultiMachineNameSpace instance;
    private final OpcUaServer server;
    private final SubscriptionModel subscriptionModel;
    private final AtomicInteger nodeCounter = new AtomicInteger(1);
    private final UaFolderNode rootFolder;
    private final List<UnitLogic> machines = new ArrayList<>();
    private final List<ProductionLineController> lineControllers = new ArrayList<>();
    private final Map<String, UaVariableNode> commandNodes = new ConcurrentHashMap<>();

    private static NodeId dataTypeIdFor(Object v) {
        if (v instanceof Boolean) return Identifiers.Boolean;
        if (v instanceof Byte || v instanceof Short || v instanceof Integer) return Identifiers.Int32;
        if (v instanceof Long) return Identifiers.Int64;
        if (v instanceof Float || v instanceof Double) return Identifiers.Double;
        if (v instanceof String) return Identifiers.String;
        if (v instanceof DateTime) return Identifiers.DateTime;
        return Identifiers.BaseDataType;
    }

    public MultiMachineNameSpace(OpcUaServer server, String namespaceUri) {
        super(server, namespaceUri);
        this.server = server;
        this.subscriptionModel = new SubscriptionModel(server, this);
        getLifecycleManager().addLifecycle(subscriptionModel);
//        instance = this;

        UShort nsIdx = getNamespaceIndex();

        // "Machines" 루트 폴더 생성
        rootFolder = new UaFolderNode(
                getNodeContext(),
                new NodeId(nsIdx, "Machines"),
                new QualifiedName(nsIdx, "Machines"),
                LocalizedText.english("Machines")
        );

        // Node 등록
        getNodeContext().getNodeManager().addNode(rootFolder);

    }

    public synchronized int nextNodeId() {
        return nodeCounter.getAndIncrement();
    }

    public void publishInitial(UaVariableNode node) {
        DataValue v = node.getValue();
        node.setValue(new DataValue(v.getValue(), StatusCode.GOOD, DateTime.now(), DateTime.now()));
    }

    /** 루트 폴더 반환 */
//    public UaFolderNode getRootFolder() {
//        return rootFolder;
//    }

    /**
     * 서버 시작 시 호출되어 노드 트리 구조를 초기화하는 메서드
     */
    public void initializeNodes() {
        System.out.println("[DEBUG] AddressSpaceManager class: "
                + getServer().getAddressSpaceManager().getClass().getName());

        // forward reference: source = NS0 ObjectsFolder, target = our Machines
        getNodeContext().getNodeManager().addReference(new Reference(
                Identifiers.ObjectsFolder,                    // source (NS0)
                Identifiers.Organizes,                        // reference type
                rootFolder.getNodeId().expanded(),            // target
                true                                          // forward = parent→child
        ));

        // 예시 변수 노드(정방향으로 부모=Machines에 달아줌)
//        addVariableNode(rootFolder, "Factory.Status", "RUNNING");

        UaFolderNode line01Folder = addFolder(rootFolder, "Line01");
        ProductionLineController line01Controller = new ProductionLineController(this, "Line01", line01Folder);
        lineControllers.add(line01Controller);

        UnitLogic trayCleaner01 = new TrayCleaner01("TrayCleaner01", addFolder(line01Folder, "TrayCleaner01"), this);
        UnitLogic trayCleaner02 = new TrayCleaner02("TrayCleaner02", addFolder(line01Folder, "TrayCleaner02"), this);
        UnitLogic electrodeUnit01 = new ElectrodeUnit01("ElectrodeUnit01", addFolder(line01Folder, "ElectrodeUnit01"), this);
        UnitLogic assemblyUnit01 = new AssemblyUnit01("AssemblyUnit01", addFolder(line01Folder, "AssemblyUnit01"), this);
        UnitLogic formationUnit01 = new FormationUnit01("FormationUnit01", addFolder(line01Folder, "FormationUnit01"), this);
        UnitLogic modulePackUnit01 = new ModulAndPackUnit01("ModuleAndPackUnit01", addFolder(line01Folder, "ModuleAndPackUnit01"), this);
        UnitLogic cellCleaner01 = new CellCleaner01("CellCleaner01", addFolder(line01Folder, "CellCleaner01"), this);
        UnitLogic finalInspection01 = new FinalInspection01("FinalInspection01", addFolder(line01Folder, "FinalInspection01"), this);

        registerMachine(trayCleaner01, line01Controller);
        registerMachine(trayCleaner02, line01Controller);
        registerMachine(electrodeUnit01, line01Controller);
        registerMachine(assemblyUnit01, line01Controller);
        registerMachine(formationUnit01, line01Controller);
        registerMachine(modulePackUnit01, line01Controller);
        registerMachine(cellCleaner01, line01Controller);
        registerMachine(finalInspection01, line01Controller);

        System.out.println("[MultiMachineNameSpace] Machines initialized successfully.");
        System.out.println("[MultiMachineNameSpace] ObjectsFolder initialized successfully.");
        System.out.println("[DEBUG] namespace index: " + getNamespaceIndex());

    }

    /** 변수 노드 생성 */
    public UaVariableNode addVariableNode(UaFolderNode parent, String name, Object initialValue) {
        NodeId nodeId = new NodeId(getNamespaceIndex(), nextNodeId());

        UaVariableNode node = UaVariableNode.builder(getNodeContext())
                .setNodeId(nodeId)
                .setBrowseName(new QualifiedName(getNamespaceIndex(), name)) // ← "state" 같은 순수 키
                .setDisplayName(LocalizedText.english(name))
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setValueRank(ValueRanks.Scalar)
                .setMinimumSamplingInterval(100.0)
                .setAccessLevel(AccessLevel.toValue(EnumSet.of(AccessLevel.CurrentRead, AccessLevel.CurrentWrite)))
                .setUserAccessLevel(AccessLevel.toValue(EnumSet.of(AccessLevel.CurrentRead, AccessLevel.CurrentWrite)))
                .setDataType(dataTypeIdFor(initialValue))                    // ← 지정
                .setValue(new DataValue(new Variant(initialValue)))
                .build();

        // 초기값
        node.setValue(new DataValue(new Variant(initialValue), StatusCode.GOOD, DateTime.now(), DateTime.now()));

        getNodeContext().getNodeManager().addNode(node);
        parent.addReference(new Reference(parent.getNodeId(), Identifiers.Organizes, node.getNodeId().expanded(), true));
        publishInitial(node);
        System.out.printf("[Telemetry-Init] %s = %s%n", name, String.valueOf(initialValue));
        return node;

//        getNodeContext().getNodeManager().addNode(node);

//        parent.addReference(new Reference(
//                parent.getNodeId(),
//                Identifiers.Organizes,
//                node.getNodeId().expanded(),
//                true
//        ));

//        System.out.println("[DEBUG] " + name + " DT=" + node.getDataType() +
//                " Value=" + node.getValue().getValue() +
//                " SC=" + node.getValue().getStatusCode());

    }

    public UaFolderNode addFolder(UaFolderNode parent, String folderName) {
        UaFolderNode folder = new UaFolderNode(
                getNodeContext(),
                new NodeId(getNamespaceIndex(), nextNodeId()),
                new QualifiedName(getNamespaceIndex(), folderName),
                LocalizedText.english(folderName)
        );
        getNodeContext().getNodeManager().addNode(folder);
        parent.addReference(new Reference(parent.getNodeId(), Identifiers.Organizes, folder.getNodeId().expanded(), true));
        return folder;
    }

    /** 값 갱신 및 구독자 알림 */
    public void updateValue(UaVariableNode node, Object newValue) {
        node.setValue(new DataValue(new Variant(newValue), StatusCode.GOOD, DateTime.now(), DateTime.now()));
    }

    public List<UnitLogic> getMachines() {
        return machines;
    }

    private void registerMachine(UnitLogic machine, ProductionLineController lineController) {
        machines.add(machine);
        registerCommandNode(machine.machineFolder, machine);
        if (lineController != null) {
            lineController.registerMachine(machine);
        }
        machine.startSimulation(this);
    }

    private void registerCommandNode(UaFolderNode folder, UnitLogic machine) {
        String browseName = ".command";
        UaVariableNode commandNode = UaVariableNode.builder(getNodeContext())
                .setNodeId(new NodeId(getNamespaceIndex(), nextNodeId()))
                .setBrowseName(new QualifiedName(getNamespaceIndex(), machine.getName() + browseName))
                .setDisplayName(LocalizedText.english(machine.getName() + " Command"))
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setValueRank(ValueRanks.Scalar)
                .setDataType(Identifiers.String)
                .setMinimumSamplingInterval(0.0)
                .setAccessLevel(AccessLevel.toValue(EnumSet.of(AccessLevel.CurrentRead, AccessLevel.CurrentWrite)))
                .setUserAccessLevel(AccessLevel.toValue(EnumSet.of(AccessLevel.CurrentRead, AccessLevel.CurrentWrite)))
                .setValue(new DataValue(new Variant(""), StatusCode.GOOD, DateTime.now(), DateTime.now()))
                .build();

        commandNode.setAttributeDelegate(new AttributeDelegate() {
            @Override
            public void setAttribute(AttributeContext context, org.eclipse.milo.opcua.sdk.core.nodes.Node node, AttributeId attributeId, DataValue value) throws UaException {
                AttributeDelegate.super.setAttribute(context, node, attributeId, value);

                if (attributeId == AttributeId.Value) {
                    Variant raw = value.getValue();
                    String command = raw != null && raw.getValue() != null ? raw.getValue().toString().trim() : "";
                    if (!command.isEmpty()) {
                        machine.onCommand(MultiMachineNameSpace.this, command);
                    }
                }
            }
        });

        getNodeContext().getNodeManager().addNode(commandNode);
        folder.addReference(new Reference(folder.getNodeId(), Identifiers.Organizes, commandNode.getNodeId().expanded(), true));
        commandNodes.put(machine.getName(), commandNode);
    }

    /* ---------- MonitoredItemServices 구현 ---------- */
    @Override
    public void onDataItemsCreated(List<DataItem> items) {
        items.forEach(item -> System.out.printf(
                "[SubscriptionModel] onDataItemsCreated: id=%s sampling=%.2f%n",
                item.getId(),
                item.getSamplingInterval()
        ));
        subscriptionModel.onDataItemsCreated(items);
    }

    @Override
    public void onDataItemsModified(List<DataItem> items) {
        System.out.println("[SubscriptionModel] onDataItemsModified: " + items.size());
        subscriptionModel.onDataItemsModified(items);
    }

    @Override
    public void onDataItemsDeleted(List<DataItem> items) {
        items.forEach(item -> System.out.printf(
                "[SubscriptionModel] onDataItemsDeleted: id=%s%n",
                item.getId()
        ));
        subscriptionModel.onDataItemsDeleted(items);
    }

    @Override
    public void onMonitoringModeChanged(List<MonitoredItem> items) {
        items.forEach(item -> System.out.printf(
                "[SubscriptionModel] onMonitoringModeChanged: id=%s samplingEnabled=%s%n",
                item.getId(),
                item.isSamplingEnabled()
        ));
        subscriptionModel.onMonitoringModeChanged(items);
    }

}
