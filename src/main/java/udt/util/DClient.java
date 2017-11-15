package udt.util;

import java.io.IOException;
import java.net.InetAddress;
import java.text.NumberFormat;

import udt.ClientSession;
import udt.UDTClient;
import udt.UDTInputStream;
import udt.UDTInputStream.AppData;
import udt.UDTReceiver;
import udt.UDTSession;
import udt.packets.DataPacket;

/**
 * ���󲢽���DServer�������ݵĿͻ��˳������ڵ��ԺͶ���
 */
public class DClient extends Application{

	private final NumberFormat format;
	private UDTSession mySession;
	
	public DClient(){
		format=NumberFormat.getNumberInstance();
		format.setMaximumFractionDigits(3);
	}
	
	public void run(){
		configure();
		verbose=false;//true;
		System.out.println("DServer connected ....");
		try{
			UDTSession session = mySession;
			UDTInputStream in=session.getSocket().getInputStream();
			long start = System.currentTimeMillis();
		    //and read the file data
			
			byte[]buf=new byte[65536];
			int c;
			long read=0;
			while(read < 1024*1024){
				c=in.read(buf);
				if(c<0)break;
				read+=c;
				// System.out.println("writing <"+c+"> bytes total <"+read+"> bytes");
			}
			long end = System.currentTimeMillis();
			double rate=1000.0*read/1024/1024/(end-start);
			System.out.println("Receive Rate: "+format.format(rate)+" MBytes/sec. "
					+format.format(8*rate)+" MBit/sec.");
		
			session.shutdown();
			
			System.out.println(session.getStatistics());
		}catch(Exception ex){
			throw new RuntimeException(ex);
		}
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
		
		DClient rf=new DClient();
		UDTReceiver.connectionExpiryDisabled=true;
		InetAddress myHost=InetAddress.getLocalHost();
		UDTClient client=new UDTClient(myHost,0) {
			long time_passed;
			@Override
			public void UDTClientConnected(UDTSession session) {
				rf.mySession = session;
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

						time_passed = System.currentTimeMillis() - time_passed;
						double rate=1000.0 / time_passed;
						System.out.println(session.getStatistics());
						System.out.println("Receive Rate: "+ rate+ " MBytes/sec. ");
						
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
