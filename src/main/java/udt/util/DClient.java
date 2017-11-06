package udt.util;

import java.net.InetAddress;
import java.text.NumberFormat;

import udt.UDTClient;
import udt.UDTInputStream;
import udt.UDTReceiver;

/**
 * 请求并接收DServer发送数据的客户端程序，用于调试和度量
 */
public class DClient extends Application{

	private final NumberFormat format;
	private UDTClient myClient;
	
	public DClient(){
		format=NumberFormat.getNumberInstance();
		format.setMaximumFractionDigits(3);
	}
	
	public void run(){
		configure();
		verbose=true;
		try{
			UDTClient client = myClient;
			UDTInputStream in=client.getInputStream();
			
			System.out.println("Requesting DServer....");
			long start = System.currentTimeMillis();
		    //and read the file data
			
			byte[]buf=new byte[65536];
			int c;
			long read=0;
			while(read < 10*1024){
				c=in.read(buf);
				if(c<0)break;
				read+=c;
				//System.out.println("writing <"+c+"> bytes total <"+read+"> bytes");
			}
			long end = System.currentTimeMillis();
			double rate=1000.0*read/1024/1024/(end-start);
			System.out.println("[ReceiveFile] Rate: "+format.format(rate)+" MBytes/sec. "
					+format.format(8*rate)+" MBit/sec.");
		
			client.shutdown();
			
			if(verbose)System.out.println(client.getStatistics());
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
			public void UDTClientConnected(UDTClient client) {
				rf.myClient = client;
				rf.run();
			}
		};
		
		client.connect(serverHost, serverPort);
	}
	
	public static void usage(){
		System.out.println("Usage: java -cp .. udt.util.DClient <server_ip>");
	}
}
