package udt;

import udt.packets.DataPacket;

public interface SessionHandlers {
	public void onDataRequest();
	public boolean onDataReceive(UDTSession session, DataPacket packet);
	public void onSessionEnd(UDTSession session);
	public void onShutdown();
}
