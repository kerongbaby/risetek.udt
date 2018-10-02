
package udt.util;
import java.net.InetAddress;
import java.net.SocketException;
import java.text.NumberFormat;

import udt.ServerSession;
import udt.UDPEndPoint;
import udt.packets.DataPacket;
import udt.packets.Destination;

public class DServer extends ServerSession {

	private int sendCounter = 0;
	private byte[] buf=new byte[packetSize];
	private long period = System.currentTimeMillis();

	public DServer(Destination peer, UDPEndPoint endPoint) throws SocketException {
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
		
		// for(int index = 0; index < 10; index++)
		for(;;)
		{
			if(sendCounter >= numberPackets)
				return false;

			if(write(buf, packetSize) == 0) {
				// System.out.println("flowWindow fulled at: " + sendCounter);
				break;
			} else
				sendCounter++;
		}
		return true;
	}

	@Override
	public void onSessionReady() {
		startSender();
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
		System.out.println("datas coming...");
		return false;
	}
	

	private final static int packetSize = 512;
	private final static int numberPackets = (8*1024*1024)/packetSize;

	public static void main(String[] fullArgs) throws Exception{
		System.out.println("listing on 18008");

		try{
			new UDPEndPoint(InetAddress.getLocalHost(), 18008){

				@Override
				public ServerSession onSessionCreate(Destination peer, UDPEndPoint endPoint) throws SocketException {

					return new DServer(peer, endPoint);
				}
		};
			
		}catch(Exception ex){
			throw new RuntimeException(ex);
		}

		Thread.currentThread().join();
	}
}
