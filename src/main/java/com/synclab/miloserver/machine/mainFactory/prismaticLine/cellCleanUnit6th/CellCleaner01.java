package com.synclab.miloserver.machine.mainFactory.prismaticLine.cellCleanUnit6th;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class CellCleaner01 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.cellCleanUnit6th.CellCleaner01 {

    public CellCleaner01(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "PrismaticLine", equipmentCode, folder, ns);
    }
}
