
package udt.util;

import java.io.OutputStream;
import java.net.InetAddress;
import java.text.NumberFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import udt.UDTOutputStream;
import udt.UDTReceiver;
import udt.UDTServerSocket;
import udt.UDTSession;


/**
 * 一个随机发送1M字节的服务端程序，用于调试和测量目的
 */
public class DServer extends Application{

	private final int serverPort;
	private final static int numberPackets = 1024;

	//TODO configure pool size
	private final ExecutorService threadPool=Executors.newFixedThreadPool(3);

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
				threadPool.execute(new RequestRunner(session));
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

	public static class RequestRunner implements Runnable{

		private final static Logger logger=Logger.getLogger(RequestRunner.class.getName());

		private final UDTSession session;

		private final NumberFormat format=NumberFormat.getNumberInstance();

		public RequestRunner(UDTSession session){
			this.session=session;
			format.setMaximumFractionDigits(3);
		}

		public void run(){
			try{
				logger.info("Handling request from "+session.getDestination());
				System.out.println("begin sending data.");
				UDTOutputStream out=session.getSocket().getOutputStream();
				
				try{
					long start=System.currentTimeMillis();
					//and send the file
					sendDatas(session, out);

					System.out.println(" Finished sending data.");
					long end=System.currentTimeMillis();
					System.out.println(session.getStatistics().toString());
					double rate=1000.0*numberPackets*1024/1024/1024/(end-start);
					System.out.println("[SendFile] Rate: "+format.format(rate)+" MBytes/sec. "+format.format(8*rate)+" MBit/sec.");
				}finally{
					session.getSocket().getSender().stop();
					session.getSocket().close();
				}
				logger.info("Finished request from "+session.getDestination());
			}catch(Exception ex){
				ex.printStackTrace();
				throw new RuntimeException(ex);
			}
		}
	}

	private static void sendDatas(UDTSession session, OutputStream os)throws Exception{
		byte[]buf=new byte[1024];
		for(int looper = 0; looper < numberPackets; looper++) {
			System.out.print(" " + looper);
			os.write(buf, 0, 1024);
		}
		session.getSocket().flush();
//		os.flush();
	}	
}
