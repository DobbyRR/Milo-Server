package com.synclab.miloserver.machine.mainFactory.prismaticLine.electrodeUnit2nd;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class ElectrodeUnit02 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.electrodeUnit2nd.ElectrodeUnit02 {

    public ElectrodeUnit02(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "PrismaticLine", equipmentCode, folder, ns);
    }
}
