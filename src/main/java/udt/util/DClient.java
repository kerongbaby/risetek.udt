package udt.util;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import udt.AppData;
import udt.ClientSession;
import udt.UDPEndPoint;
import udt.UDTSession;
import udt.packets.DataPacket;
import udt.packets.Destination;

public class DClient extends ClientSession {

	public DClient(UDPEndPoint client, Destination destination) throws SocketException {
		super(client, destination);
	}

	private long time_passed;

	@Override
	public void onSessionReady() {
		time_passed = System.currentTimeMillis();
	}

	@Override
	public void onShutdown() {
		System.out.println("session shutdown");
	}

	@Override
	public void onSessionPrepare() {

	}

	@Override
	public boolean onSessionDataRequest() {
		return true;
	}

	@Override
	public void onSessionEnd() {
		System.out.println(getStatistics());

		NumberFormat format = NumberFormat.getNumberInstance();
		format.setMaximumFractionDigits(3);
		time_passed = System.currentTimeMillis() - time_passed;
		double rate= getTransferSize() / 1000.0 / time_passed;
		System.out.println("Receive Rate: "+ format.format(rate)+ " MBytes/sec. ");
	}

	private long tansferSize = 0;
	
	@Override
	public boolean onDataReceive(DataPacket packet) {
		for(;;) {
			AppData data;
			if((data = receiveBuffer.poll()) == null)
				break;
			
			tansferSize += data.data.length;

			if(tansferSize >= getTransferSize())
			{
				try {
					shutdown();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

		}
		return true;
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
		
		UDPEndPoint endPoint =new UDPEndPoint(InetAddress.getLocalHost()) {
			@Override
			public UDTSession onSessionCreate(Destination peer, UDPEndPoint endPoint)
					throws SocketException, IOException {
				ClientSession session = new DClient(this, peer);
				session.connect();
				return session;
			}

		};
		
		//create client session...
		InetAddress address=InetAddress.getByName(serverHost);
		Destination destination=new Destination(address,serverPort);

		endPoint.createClientSession(destination);
		
		// TODO: 如果我们要发送数据，可以获取session,并发送。
	}
	
	
	public static void usage(){
		System.out.println("Usage: java -cp .. udt.util.DClient <server_ip>");
	}
	
	protected static boolean verbose=false;

	protected static String localIP=null;

	protected static int localPort=-1;

	public void configure(){
		if(verbose){
			Logger.getLogger("udt").setLevel(Level.INFO);
		}
		else{
			Logger.getLogger("udt").setLevel(Level.OFF);
		}
	}
	
	
	protected static String[] parseOptions(String[] args){
		List<String>newArgs=new ArrayList<String>();
		for(String arg: args){
			if(arg.startsWith("-")){
				parseArg(arg);
			}
			else
			{
				newArgs.add(arg);
			}
		}
		return newArgs.toArray(new String[newArgs.size()]);
	}
	
	protected static void parseArg(String arg){
		if("-v".equals(arg) || "--verbose".equals(arg)){
			verbose=true;
			return;
		}
		if(arg.startsWith("--localIP")){
			localIP=arg.split("=")[1];
		}
		if(arg.startsWith("--localPort")){
			localPort=Integer.parseInt(arg.split("=")[1]);
		}
	}
}
