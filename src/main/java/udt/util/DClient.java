package udt.util;

import java.io.IOException;
import java.net.InetAddress;
import java.text.NumberFormat;

import udt.UDTClient;
import udt.UDTInputStream.AppData;
import udt.UDTReceiver;
import udt.UDTSession;
import udt.packets.DataPacket;

/**
 * 请求并接收DServer发送数据的客户端程序，用于调试和度量
 */
public class DClient extends Application{
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
		
		UDTReceiver.connectionExpiryDisabled=true;
		InetAddress myHost=InetAddress.getLocalHost();
		UDTClient client=new UDTClient(myHost,0) {
			long time_passed;
			@Override
			public void UDTClientConnected(UDTSession session) {
				System.out.println("getInitialSequenceNumber:" + session.getInitialSequenceNumber());
				time_passed = System.currentTimeMillis();
			}

			private int count = 0;
			@Override
			public boolean onDataPacketReceived(UDTSession session, DataPacket dp) {
				// System.out.println("data: " + (count++) + " seq:" + dp.getPacketSequenceNumber());
				if(!session.receiveBuffer.offer(new AppData((dp.getPacketSequenceNumber()-session.getInitialSequenceNumber()), dp.getData()))) {
					System.out.println("data packet overload");
					return false;
				}
				
				boolean haveone = false;
				for(;;) {
					AppData data;
					if((data = session.receiveBuffer.poll()) == null)
						break;
					haveone = true;
					// System.out.print(" " +  data.getSequenceNumber());
					
					if(1023 == data.getSequenceNumber()) {
						try {
							session.shutdown();
						} catch (IOException e) {
							e.printStackTrace();
						}

						NumberFormat format = NumberFormat.getNumberInstance();
						format.setMaximumFractionDigits(3);
						time_passed = System.currentTimeMillis() - time_passed;
						double rate=1000.0 / time_passed;
						System.out.println(session.getStatistics());
						System.out.println("Receive Rate: "+ format.format(rate)+ " MBytes/sec. ");
						
						synchronized(this) {
							notify();
						}
					}
				}
				// if(haveone)	System.out.println();
				return true;
			}
		};
		
		client.connect(serverHost, serverPort);
		
		synchronized(client) {
			client.wait();
		}
		System.out.println("bye my world!");
	}
	
	public static void usage(){
		System.out.println("Usage: java -cp .. udt.util.DClient <server_ip>");
	}
}
