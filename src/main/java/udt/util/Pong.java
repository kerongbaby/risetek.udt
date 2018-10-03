
package udt.util;
import java.net.InetAddress;
import java.net.SocketException;
import java.text.NumberFormat;

import udt.ServerSession;
import udt.UDPEndPoint;
import udt.packets.DataPacket;
import udt.packets.Destination;

public class Pong extends ServerSession {

	private long period = System.currentTimeMillis();

	public Pong(Destination peer, UDPEndPoint endPoint) throws SocketException {
		super(peer, endPoint);
	}

	@Override
	public void onShutdown() {
		System.out.println("server session shutdown");
	}

	@Override
	public void onSessionPrepare() {
		setTransferSize(packetSize * numberPackets);
	}

	@Override
	public boolean onSessionDataRequest() {
		return true;
	}

	@Override
	public void onSessionReady() {
		// startSender();
	}

	@Override
	public void onSendEmpty() {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void onSessionEnd() {
		System.out.println(getStatistics());
		NumberFormat format = NumberFormat.getNumberInstance();
		format.setMaximumFractionDigits(3);
		period = System.currentTimeMillis() - period;
		double rate= numberPackets * packetSize / 1000.0 / period;
		System.out.println("Receive Rate: "+ format.format(rate)+ " MBytes/sec. ");
	}

	@Override
	public boolean onDataReceive(DataPacket packet) {
		for(;receiveBuffer.poll() != null;);
		return true;
	}
	

	private final static int packetSize = 512;
	private final static int numberPackets = (8*1024*1024)/packetSize;

	public static void main(String[] fullArgs) throws Exception{
		System.out.println("listing on 18008");

		try{
			new UDPEndPoint(InetAddress.getLocalHost(), 18008){

				@Override
				public ServerSession onSessionCreate(Destination peer, UDPEndPoint endPoint) throws SocketException {

					return new Pong(peer, endPoint);
				}
		};
			
		}catch(Exception ex){
			throw new RuntimeException(ex);
		}

		Thread.currentThread().join();
	}

}
