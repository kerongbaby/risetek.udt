
package udt.util;
import java.io.IOException;
import java.text.NumberFormat;

import udt.SessionHandlers;
import udt.UDTServerSocket;
import udt.UDTSession;
import udt.packets.DataPacket;


/**
 * 一个随机发送1M字节的服务端程序，用于调试和测量目的
 */
public class DServer {

	private final static int numberPackets = 1024;

	public static void main(String[] fullArgs) throws Exception{
		System.out.println("listing on 18008");

		try{
			new UDTServerSocket(18008){

				@Override
				public void onSessionAccept(UDTSession session) {
					System.out.println("new session accept!!!!!");
					RequestRunner runner = new RequestRunner(session);
					session.registeSessionHandlers(runner);
				}
				
			};
			
			while(true){
				Thread.sleep(100);
			}
		}catch(Exception ex){
			throw new RuntimeException(ex);
		}
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
					// System.out.println("end of sending");
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

		@Override
		public void onSessionEnd(UDTSession session) {
			System.out.println(session.getStatistics());
			NumberFormat format = NumberFormat.getNumberInstance();
			format.setMaximumFractionDigits(3);
			period = System.currentTimeMillis() - period;
			double rate=1000.0 / period;
			System.out.println("Receive Rate: "+ format.format(rate)+ " MBytes/sec. ");
		}
	}
}
