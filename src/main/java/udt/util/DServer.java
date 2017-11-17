
package udt.util;
import java.io.IOException;
import java.net.InetAddress;
import java.text.NumberFormat;
import udt.SessionHandlers;
import udt.UDTReceiver;
import udt.UDTServerSocket;
import udt.UDTSession;
import udt.packets.DataPacket;


/**
 * 一个随机发送1M字节的服务端程序，用于调试和测量目的
 */
public class DServer extends Application {

	private final int serverPort;
	private final static int numberPackets = 1024;

	public DServer(int serverPort){
		this.serverPort=serverPort;
	}

	@Override
	public void configure(){
		super.configure();
	}

	public void run(){
		configure();
		try{
			UDTReceiver.connectionExpiryDisabled=true;
			InetAddress myHost=localIP!=null?InetAddress.getByName(localIP):InetAddress.getLocalHost();
			UDTServerSocket server=new UDTServerSocket(myHost,serverPort);
			while(true){
				UDTSession session=server.accept();
				System.out.println("session accepted");
				RequestRunner runner = new RequestRunner(session);
				session.registeSessionHandlers(runner);
				// threadPool.execute(runner);
			}
		}catch(Exception ex){
			throw new RuntimeException(ex);
		}
	}

	/**
	 * main() method for invoking as a commandline application
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] fullArgs) throws Exception{
		System.out.println("listing on 18008");
		DServer sf=new DServer(18008);
		sf.run();
	}

	public static class RequestRunner implements SessionHandlers{

		private final UDTSession session;

		private final NumberFormat format=NumberFormat.getNumberInstance();

		byte[]buf=new byte[1024];

		long period = System.currentTimeMillis();
		
		public RequestRunner(UDTSession session){
			this.session=session;
			format.setMaximumFractionDigits(3);
		}

		private int sendCounter = 0;
		@Override
		public void onDataRequest() {
			for(;;) 
			{
				if(sendCounter >= numberPackets) {
					System.out.println("end of sending");
/*
					try {
						session.getSocket().flush();
						session.getSocket().getSender().stop();
						session.getSocket().close();
					} catch (InterruptedException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
	
					System.out.println(" Finished sending data.");
					System.out.println(session.getStatistics().toString());
	
					period = System.currentTimeMillis() - period;
					double rate=1000.0*numberPackets*1024/1024/1024/period;
					System.out.println("[Send Packet] Rate: "+format.format(rate)+" MBytes/sec.");
*/
					return;
				}
	
				// System.out.println("request to send:" + sendCounter);
				try {
					int len;
					if((len = session.write(buf, 1024)) < 1024) {
						System.out.println("short send: " + len + "/1024");
						break;
					} else
						sendCounter++;
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}

		@Override
		public boolean onDataReceive(UDTSession session, DataPacket packet) {
			System.out.println("datas coming...");
			return false;
		}

		@Override
		public void onShutdown() {
			System.out.println("sesion shutdown");
		}
	}
}
