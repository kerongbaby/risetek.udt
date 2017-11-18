package udt.util;

import java.io.IOException;
import java.net.InetAddress;
import java.text.NumberFormat;

import udt.AppData;
import udt.SessionHandlers;
import udt.UDTClient;
import udt.UDTSession;
import udt.packets.DataPacket;

/**
 * 请求并接收DServer发送数据的客户端程序，用于调试和度量
 */
public class DClient extends Application implements SessionHandlers {
	long time_passed;

	public void run(){
		System.out.println("do nothing");
	}
	
	public static void main(String[] fullArgs) throws Exception{
		int serverPort=18008;
		String serverHost="localhost";
		String[] args=parseOptions(fullArgs);
		
		try{
			serverHost=args[0];
		}catch(Exception ex){
			usage();
			System.exit(1);
		}
		
		DClient dclient = new DClient();
		
		InetAddress myHost=InetAddress.getLocalHost();
		UDTClient client=new UDTClient(myHost) {
			@Override
			public void onSessionReady(UDTSession session) {
				dclient.time_passed = System.currentTimeMillis();
				session.registeSessionHandlers(dclient);
			}
		};
		
		client.connect(serverHost, serverPort);
		
		synchronized(dclient) {
			dclient.wait();
		}
		System.out.println("bye my world!");
	}
	
	public static void usage(){
		System.out.println("Usage: java -cp .. udt.util.DClient <server_ip>");
	}

	@Override
	public void onDataRequest() {
		// do nothing
	}

	@Override
	public boolean onDataReceive(UDTSession session, DataPacket packet) {
		for(;;) {
			AppData data;
			if((data = session.receiveBuffer.poll()) == null)
				break;
			
			if(1023 == data.getSequenceNumber()) {
				try {
					session.shutdown();
				} catch (IOException e) {
					e.printStackTrace();
				}
				System.out.println(session.getStatistics());

				NumberFormat format = NumberFormat.getNumberInstance();
				format.setMaximumFractionDigits(3);
				time_passed = System.currentTimeMillis() - time_passed;
				double rate=1000.0 / time_passed;
				System.out.println("Receive Rate: "+ format.format(rate)+ " MBytes/sec. ");

				synchronized(this) {
					notify();
				}
			}
		}
		return true;
		}

	@Override
	public void onShutdown() {
		System.out.println("session shutdown");
	}

	@Override
	public void onSessionEnd(UDTSession session) {
		System.out.println("session end");
	}
}
