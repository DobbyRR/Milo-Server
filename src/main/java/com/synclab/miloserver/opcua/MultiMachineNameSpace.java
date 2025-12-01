package com.synclab.miloserver.opcua;

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

import com.synclab.miloserver.machine.mainFactory.cylindricalLine.assemblyUnit3rd.AssemblyUnit01;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.assemblyUnit3rd.AssemblyUnit02;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.cellCleanUnit6th.CellCleaner01;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.cellCleanUnit6th.CellCleaner02;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.electrodeUnit2nd.ElectrodeUnit01;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.electrodeUnit2nd.ElectrodeUnit02;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.finalInspection.FinalInspection01;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.finalInspection.FinalInspection02;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.formationUnit4th.FormationUnit01;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.formationUnit4th.FormationUnit02;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.moduleAndPackUnit5th.ModuleAndPackUnit01;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.moduleAndPackUnit5th.ModuleAndPackUnit02;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.trayCleanUnit1st.TrayCleaner01;
import com.synclab.miloserver.machine.mainFactory.cylindricalLine.trayCleanUnit1st.TrayCleaner02;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 공장/라인/설비 계층 구조와 각 UnitLogic의 telemetry 노드를 한 번에 구성하는 핵심 네임스페이스.
 * CtrlLine에서 설명한 "Machines 루트 → factory.line.machine.tag" 규칙이 여기서 생성된다.
 */
public class MultiMachineNameSpace extends ManagedNamespaceWithLifecycle {

    private static MultiMachineNameSpace instance;
    private final OpcUaServer server;
    private final SubscriptionModel subscriptionModel;
    private final AtomicInteger nodeCounter = new AtomicInteger(1);
    private final UaFolderNode rootFolder;
    private final List<UnitLogic> machines = new ArrayList<>();
    private final List<EnvironmentProbe> environmentProbes = new ArrayList<>();
    private final List<ProductionLineController> lineControllers = new ArrayList<>();
    private final Map<String, ProductionLineController> lineControllersByKey = new ConcurrentHashMap<>();
    private final Map<String, UaVariableNode> commandNodes = new ConcurrentHashMap<>();

    private enum LineVariant {
        CYLINDRICAL,
        PRISMATIC,
        COMPOSITE
    }

    private static final class FactoryProfile {
        private final String factoryCode;
        private final double baseTemperature;
        private final double baseHumidity;
        private final List<LineProfile> lines;

        private FactoryProfile(String factoryCode,
                               double baseTemperature,
                               double baseHumidity,
                               List<LineProfile> lines) {
            this.factoryCode = factoryCode;
            this.baseTemperature = baseTemperature;
            this.baseHumidity = baseHumidity;
            this.lines = lines;
        }
    }

    private static final class LineProfile {
        private final String lineCode;
        private final String lineId;
        private final String equipmentPrefix;
        private final boolean legacyMachineNames;
        private final LineVariant variant;
        private final int sequenceIndex;

        private LineProfile(String lineCode,
                            String lineId,
                            String equipmentPrefix,
                            boolean legacyMachineNames,
                            LineVariant variant,
                            int sequenceIndex) {
            this.lineCode = lineCode;
            this.lineId = lineId;
            this.equipmentPrefix = equipmentPrefix;
            this.legacyMachineNames = legacyMachineNames;
            this.variant = variant;
            this.sequenceIndex = sequenceIndex;
        }
    }

    private static NodeId dataTypeIdFor(Object v) {
        if (v instanceof Boolean) return Identifiers.Boolean;
        if (v instanceof Byte || v instanceof Short || v instanceof Integer) return Identifiers.Int32;
        if (v instanceof Long) return Identifiers.Int64;
        if (v instanceof Float || v instanceof Double) return Identifiers.Double;
        if (v instanceof String) return Identifiers.String;
        if (v instanceof DateTime) return Identifiers.DateTime;
        return Identifiers.BaseDataType;
    }

    private static String lineQualifiedName(String factoryCode, String lineCode) {
        return factoryCode + "." + lineCode;
    }

    private void registerLineController(String factoryCode, String lineCode, ProductionLineController controller) {
        lineControllers.add(controller);
        lineControllersByKey.put(lineKey(factoryCode, lineCode), controller);
    }

    private static String lineKey(String factoryCode, String lineCode) {
        return (factoryCode + ":" + lineCode).toLowerCase();
    }

    public Optional<ProductionLineController> findLineController(String factoryCode, String lineCode) {
        if (factoryCode == null || lineCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(lineControllersByKey.get(lineKey(factoryCode, lineCode)));
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

        // CtrlLine 문서의 공장/라인 프로필(F0001~F0003, CL/PL/CP)을 그대로 코드로 정의한다.
        List<FactoryProfile> factories = Arrays.asList(
                new FactoryProfile("F0001", 23.5, 45.0, Arrays.asList(
                        new LineProfile("CL0001", "CylindricalLine", "F1-CL1-", true, LineVariant.CYLINDRICAL, 1),
                        new LineProfile("PL0001", "PrismaticLine", "F1-PL1-", false, LineVariant.PRISMATIC, 2),
                        new LineProfile("CP0001", "CompositeLine", "F1-CP1-", false, LineVariant.COMPOSITE, 3)
                )),
                new FactoryProfile("F0002", 22.8, 42.5, Arrays.asList(
                        new LineProfile("CL0002", "CylindricalLine", "F2-CL2-", false, LineVariant.CYLINDRICAL, 4),
                        new LineProfile("PL0002", "PrismaticLine", "F2-PL2-", false, LineVariant.PRISMATIC, 5),
                        new LineProfile("CP0002", "CompositeLine", "F2-CP2-", false, LineVariant.COMPOSITE, 6)
                )),
                new FactoryProfile("F0003", 24.0, 48.0, Arrays.asList(
                        new LineProfile("CL0003", "CylindricalLine", "F3-CL3-", false, LineVariant.CYLINDRICAL, 7),
                        new LineProfile("PL0003", "PrismaticLine", "F3-PL3-", false, LineVariant.PRISMATIC, 8),
                        new LineProfile("CP0003", "CompositeLine", "F3-CP3-", false, LineVariant.COMPOSITE, 9)
                ))
        );

        for (FactoryProfile factory : factories) {
            initializeFactory(factory);
        }

        environmentProbes.forEach(EnvironmentProbe::start);

        System.out.println("[MultiMachineNameSpace] Machines initialized successfully.");
        System.out.println("[MultiMachineNameSpace] ObjectsFolder initialized successfully.");
        System.out.println("[DEBUG] namespace index: " + getNamespaceIndex());

    }

    private void initializeFactory(FactoryProfile factoryProfile) {
        UaFolderNode factoryFolder = addFolder(rootFolder, factoryProfile.factoryCode);
        registerEnvironmentProbe(factoryProfile, factoryFolder);
        for (LineProfile profile : factoryProfile.lines) {
            initializeLine(factoryFolder, factoryProfile.factoryCode, profile);
        }
    }

    private void registerEnvironmentProbe(FactoryProfile factoryProfile, UaFolderNode factoryFolder) {
        EnvironmentProbe probe = new EnvironmentProbe(
                this,
                factoryFolder,
                factoryProfile.factoryCode,
                factoryProfile.baseTemperature,
                factoryProfile.baseHumidity
        );
        environmentProbes.add(probe);
    }

    private void initializeLine(UaFolderNode factoryFolder, String factoryCode, LineProfile profile) {
        UaFolderNode lineFolder = addFolder(factoryFolder, profile.lineCode);
        ProductionLineController lineController = new ProductionLineController(
                this,
                lineQualifiedName(factoryCode, profile.lineCode),
                lineFolder
        );
        registerLineController(factoryCode, profile.lineCode, lineController);

        registerLineMachines(profile, lineFolder, lineController);
    }

    private void registerLineMachines(LineProfile profile,
                                      UaFolderNode lineFolder,
                                      ProductionLineController lineController) {
        switch (profile.variant) {
            case CYLINDRICAL -> registerCylindricalMachines(profile, lineFolder, lineController);
            case PRISMATIC -> registerPrismaticMachines(profile, lineFolder, lineController);
            case COMPOSITE -> registerCompositeMachines(profile, lineFolder, lineController);
            default -> throw new IllegalArgumentException("Unsupported line variant: " + profile.variant);
        }
    }

    private String equipmentCode(LineProfile profile, String baseCode, int unitIndex) {
        int start = (profile.sequenceIndex - 1) * 2;
        int number = start + unitIndex;
        return profile.equipmentPrefix + baseCode + String.format("%03d", number);
    }

    private void registerCylindricalMachines(LineProfile profile,
                                             UaFolderNode lineFolder,
                                             ProductionLineController lineController) {
        registerMachine(new TrayCleaner01(
                machineName(profile, "TrayCleaner01"),
                profile.lineId,
                equipmentCode(profile, "TCP", 1),
                addFolder(lineFolder, "TrayCleaner01"),
                this
        ), lineController);
        registerMachine(new TrayCleaner02(
                machineName(profile, "TrayCleaner02"),
                profile.lineId,
                equipmentCode(profile, "TCP", 2),
                addFolder(lineFolder, "TrayCleaner02"),
                this
        ), lineController);
        registerMachine(new ElectrodeUnit01(
                machineName(profile, "ElectrodeUnit01"),
                profile.lineId,
                equipmentCode(profile, "EU", 1),
                addFolder(lineFolder, "ElectrodeUnit01"),
                this
        ), lineController);
        registerMachine(new ElectrodeUnit02(
                machineName(profile, "ElectrodeUnit02"),
                profile.lineId,
                equipmentCode(profile, "EU", 2),
                addFolder(lineFolder, "ElectrodeUnit02"),
                this
        ), lineController);
        registerMachine(new AssemblyUnit01(
                machineName(profile, "AssemblyUnit01"),
                profile.lineId,
                equipmentCode(profile, "AU", 1),
                addFolder(lineFolder, "AssemblyUnit01"),
                this
        ), lineController);
        registerMachine(new AssemblyUnit02(
                machineName(profile, "AssemblyUnit02"),
                profile.lineId,
                equipmentCode(profile, "AU", 2),
                addFolder(lineFolder, "AssemblyUnit02"),
                this
        ), lineController);
        registerMachine(new FormationUnit01(
                machineName(profile, "FormationUnit01"),
                profile.lineId,
                equipmentCode(profile, "FAU", 1),
                addFolder(lineFolder, "FormationUnit01"),
                this
        ), lineController);
        registerMachine(new FormationUnit02(
                machineName(profile, "FormationUnit02"),
                profile.lineId,
                equipmentCode(profile, "FAU", 2),
                addFolder(lineFolder, "FormationUnit02"),
                this
        ), lineController);
        registerMachine(new ModuleAndPackUnit01(
                machineName(profile, "ModuleAndPackUnit01"),
                profile.lineId,
                equipmentCode(profile, "MAP", 1),
                addFolder(lineFolder, "ModuleAndPackUnit01"),
                this
        ), lineController);
        registerMachine(new ModuleAndPackUnit02(
                machineName(profile, "ModuleAndPackUnit02"),
                profile.lineId,
                equipmentCode(profile, "MAP", 2),
                addFolder(lineFolder, "ModuleAndPackUnit02"),
                this
        ), lineController);
        registerMachine(new CellCleaner01(
                machineName(profile, "CellCleaner01"),
                profile.lineId,
                equipmentCode(profile, "CCP", 1),
                addFolder(lineFolder, "CellCleaner01"),
                this
        ), lineController);
        registerMachine(new CellCleaner02(
                machineName(profile, "CellCleaner02"),
                profile.lineId,
                equipmentCode(profile, "CCP", 2),
                addFolder(lineFolder, "CellCleaner02"),
                this
        ), lineController);
        registerMachine(new FinalInspection01(
                machineName(profile, "FinalInspection01"),
                profile.lineId,
                equipmentCode(profile, "FIP", 1),
                addFolder(lineFolder, "FinalInspection01"),
                this
        ), lineController);
        registerMachine(new FinalInspection02(
                machineName(profile, "FinalInspection02"),
                profile.lineId,
                equipmentCode(profile, "FIP", 2),
                addFolder(lineFolder, "FinalInspection02"),
                this
        ), lineController);
    }

    private void registerPrismaticMachines(LineProfile profile,
                                           UaFolderNode lineFolder,
                                           ProductionLineController lineController) {
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.trayCleanUnit1st.TrayCleaner01(
                machineName(profile, "TrayCleaner01"),
                equipmentCode(profile, "TCP", 1),
                addFolder(lineFolder, "TrayCleaner01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.trayCleanUnit1st.TrayCleaner02(
                machineName(profile, "TrayCleaner02"),
                equipmentCode(profile, "TCP", 2),
                addFolder(lineFolder, "TrayCleaner02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.electrodeUnit2nd.ElectrodeUnit01(
                machineName(profile, "ElectrodeUnit01"),
                equipmentCode(profile, "EU", 1),
                addFolder(lineFolder, "ElectrodeUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.electrodeUnit2nd.ElectrodeUnit02(
                machineName(profile, "ElectrodeUnit02"),
                equipmentCode(profile, "EU", 2),
                addFolder(lineFolder, "ElectrodeUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.assemblyUnit3rd.AssemblyUnit01(
                machineName(profile, "AssemblyUnit01"),
                equipmentCode(profile, "AU", 1),
                addFolder(lineFolder, "AssemblyUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.assemblyUnit3rd.AssemblyUnit02(
                machineName(profile, "AssemblyUnit02"),
                equipmentCode(profile, "AU", 2),
                addFolder(lineFolder, "AssemblyUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.formationUnit4th.FormationUnit01(
                machineName(profile, "FormationUnit01"),
                equipmentCode(profile, "FAU", 1),
                addFolder(lineFolder, "FormationUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.formationUnit4th.FormationUnit02(
                machineName(profile, "FormationUnit02"),
                equipmentCode(profile, "FAU", 2),
                addFolder(lineFolder, "FormationUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.moduleAndPackUnit5th.ModuleAndPackUnit01(
                machineName(profile, "ModuleAndPackUnit01"),
                equipmentCode(profile, "MAP", 1),
                addFolder(lineFolder, "ModuleAndPackUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.moduleAndPackUnit5th.ModuleAndPackUnit02(
                machineName(profile, "ModuleAndPackUnit02"),
                equipmentCode(profile, "MAP", 2),
                addFolder(lineFolder, "ModuleAndPackUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.cellCleanUnit6th.CellCleaner01(
                machineName(profile, "CellCleaner01"),
                equipmentCode(profile, "CCP", 1),
                addFolder(lineFolder, "CellCleaner01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.cellCleanUnit6th.CellCleaner02(
                machineName(profile, "CellCleaner02"),
                equipmentCode(profile, "CCP", 2),
                addFolder(lineFolder, "CellCleaner02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.finalInspection.FinalInspection01(
                machineName(profile, "FinalInspection01"),
                equipmentCode(profile, "FIP", 1),
                addFolder(lineFolder, "FinalInspection01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.prismaticLine.finalInspection.FinalInspection02(
                machineName(profile, "FinalInspection02"),
                equipmentCode(profile, "FIP", 2),
                addFolder(lineFolder, "FinalInspection02"),
                this
        ), lineController);
    }

    private void registerCompositeMachines(LineProfile profile,
                                           UaFolderNode lineFolder,
                                           ProductionLineController lineController) {
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.trayCleanUnit1st.TrayCleaner01(
                machineName(profile, "TrayCleaner01"),
                equipmentCode(profile, "TCP", 1),
                addFolder(lineFolder, "TrayCleaner01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.trayCleanUnit1st.TrayCleaner02(
                machineName(profile, "TrayCleaner02"),
                equipmentCode(profile, "TCP", 2),
                addFolder(lineFolder, "TrayCleaner02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.electrodeUnit2nd.ElectrodeUnit01(
                machineName(profile, "ElectrodeUnit01"),
                equipmentCode(profile, "EU", 1),
                addFolder(lineFolder, "ElectrodeUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.electrodeUnit2nd.ElectrodeUnit02(
                machineName(profile, "ElectrodeUnit02"),
                equipmentCode(profile, "EU", 2),
                addFolder(lineFolder, "ElectrodeUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.assemblyUnit3rd.AssemblyUnit01(
                machineName(profile, "AssemblyUnit01"),
                equipmentCode(profile, "AU", 1),
                addFolder(lineFolder, "AssemblyUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.assemblyUnit3rd.AssemblyUnit02(
                machineName(profile, "AssemblyUnit02"),
                equipmentCode(profile, "AU", 2),
                addFolder(lineFolder, "AssemblyUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.formationUnit4th.FormationUnit01(
                machineName(profile, "FormationUnit01"),
                equipmentCode(profile, "FAU", 1),
                addFolder(lineFolder, "FormationUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.formationUnit4th.FormationUnit02(
                machineName(profile, "FormationUnit02"),
                equipmentCode(profile, "FAU", 2),
                addFolder(lineFolder, "FormationUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.moduleAndPackUnit5th.ModuleAndPackUnit01(
                machineName(profile, "ModuleAndPackUnit01"),
                equipmentCode(profile, "MAP", 1),
                addFolder(lineFolder, "ModuleAndPackUnit01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.moduleAndPackUnit5th.ModuleAndPackUnit02(
                machineName(profile, "ModuleAndPackUnit02"),
                equipmentCode(profile, "MAP", 2),
                addFolder(lineFolder, "ModuleAndPackUnit02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.cellCleanUnit6th.CellCleaner01(
                machineName(profile, "CellCleaner01"),
                equipmentCode(profile, "CCP", 1),
                addFolder(lineFolder, "CellCleaner01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.cellCleanUnit6th.CellCleaner02(
                machineName(profile, "CellCleaner02"),
                equipmentCode(profile, "CCP", 2),
                addFolder(lineFolder, "CellCleaner02"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.finalInspection.FinalInspection01(
                machineName(profile, "FinalInspection01"),
                equipmentCode(profile, "FIP", 1),
                addFolder(lineFolder, "FinalInspection01"),
                this
        ), lineController);
        registerMachine(new com.synclab.miloserver.machine.mainFactory.compositeLine.finalInspection.FinalInspection02(
                machineName(profile, "FinalInspection02"),
                equipmentCode(profile, "FIP", 2),
                addFolder(lineFolder, "FinalInspection02"),
                this
        ), lineController);
    }

    private String machineName(LineProfile profile, String baseName) {
        return profile.legacyMachineNames ? baseName : profile.lineId + "." + baseName;
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

        // Gateway → CtrlLine → Milo Server 명령 흐름이 이 delegate를 통해 OPC Write → onCommand()로 전달된다.
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
