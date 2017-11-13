package udt.util;

import java.net.InetAddress;
import java.text.NumberFormat;

import udt.ClientSession;
import udt.UDTClient;
import udt.UDTInputStream;
import udt.UDTReceiver;
import udt.UDTSession;

/**
 * 请求并接收DServer发送数据的客户端程序，用于调试和度量
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
			@Override
			public void UDTClientConnected(UDTSession session) {
				rf.mySession = session;
				// rf.run();
				synchronized(rf) {
					System.out.println("notify");
					rf.notify();
				}
			}
		};
		
		client.connect(serverHost, serverPort);
		synchronized(rf) {
			rf.wait();
		}
		rf.run();
	}
	
	public static void usage(){
		System.out.println("Usage: java -cp .. udt.util.DClient <server_ip>");
	}
}
