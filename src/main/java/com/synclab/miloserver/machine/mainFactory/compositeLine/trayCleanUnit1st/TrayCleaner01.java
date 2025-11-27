package com.synclab.miloserver.machine.mainFactory.compositeLine.trayCleanUnit1st;

import com.synclab.miloserver.opcua.MultiMachineNameSpace;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;

public class TrayCleaner01 extends com.synclab.miloserver.machine.mainFactory.cylindricalLine.trayCleanUnit1st.TrayCleaner01 {

    public TrayCleaner01(String name, String equipmentCode, UaFolderNode folder, MultiMachineNameSpace ns) {
        super(name, "CompositeLine", equipmentCode, folder, ns);
    }
}
