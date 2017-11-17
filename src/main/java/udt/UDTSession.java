/*********************************************************************************
 * Copyright (c) 2010 Forschungszentrum Juelich GmbH 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * (1) Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the disclaimer at the end. Redistributions in
 * binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 * 
 * (2) Neither the name of Forschungszentrum Juelich GmbH nor the names of its 
 * contributors may be used to endorse or promote products derived from this 
 * software without specific prior written permission.
 * 
 * DISCLAIMER
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *********************************************************************************/

package udt;

import java.io.IOException;
import java.net.DatagramPacket;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import udt.packets.ConnectionHandshake;
import udt.packets.DataPacket;
import udt.packets.Destination;
import udt.packets.Shutdown;
import udt.sender.FlowWindow;
import udt.util.ReceiveBuffer;
import udt.util.SequenceNumber;
import udt.util.UDTStatistics;

public abstract class UDTSession {

	private static final Logger logger=Logger.getLogger(UDTSession.class.getName());
	public abstract void connected();
	

	protected final UDPEndPoint endPoint;

	protected int mode;
	protected volatile boolean active;
	protected volatile int state=start;
	
	//state constants	
	public static final int start=0;
	public static final int handshaking=1;
	public static final int ready=50;
	public static final int keepalive=80;
	public static final int shutdown=90;
	
	public static final int invalid=99;

	//processing received data
	protected UDTReceiver receiver;
	protected UDTSender sender;
	
	protected final UDTStatistics statistics;
	
	protected int receiveBufferSize=64*32768;
	
	protected final CongestionControl cc;
	
	//cache dgPacket (peer stays the same always)
	private DatagramPacket dgPacket;

	//session cookie created during handshake
	protected long sessionCookie=0;
	
	/**
	 * flow window size, i.e. how many data packets are
	 * in-flight at a single time
	 */
	protected int flowWindowSize=256;//1024*10;

	public final FlowWindow flowWindow;
	
	/**
	 * remote UDT entity (address and socket ID)
	 */
	protected final Destination destination;
	
	/**
	 * local port
	 */
	protected int localPort;
	
	
	public static final int DEFAULT_DATAGRAM_SIZE=UDPEndPoint.DATAGRAM_SIZE;
	
	/**
	 * key for a system property defining the CC class to be used
	 * @see CongestionControl
	 */
	public static final String CC_CLASS="udt.congestioncontrol.class";
	
	/**
	 * Buffer size (i.e. datagram size)
	 * This is negotiated during connection setup
	 */
	protected int datagramSize=DEFAULT_DATAGRAM_SIZE;
	
	protected Long initialSequenceNumber=null;
	
	protected final long mySocketID;
	protected SessionHandlers sessionHandlers;
	
	private final static AtomicLong nextSocketID=new AtomicLong(20+new Random().nextInt(5000));
	public final ReceiveBuffer receiveBuffer;
	
	public void registeSessionHandlers(SessionHandlers sessionHandlers) {
		this.sessionHandlers = sessionHandlers;
	}
	
	final int chunksize;
	
	public UDTSession(String description, Destination destination, UDPEndPoint endPoint){
		this.endPoint = endPoint;
		statistics=new UDTStatistics(description);
		mySocketID=nextSocketID.incrementAndGet();
		this.destination=destination;
		this.dgPacket=new DatagramPacket(new byte[0],0,destination.getAddress(),destination.getPort());
		String clazzP=System.getProperty(CC_CLASS,UDTCongestionControl.class.getName());
		Object ccObject=null;
		try{
			Class<?>clazz=Class.forName(clazzP);
			ccObject=clazz.getDeclaredConstructor(UDTSession.class).newInstance(this);
		}catch(Exception e){
			logger.log(Level.WARNING,"Can't setup congestion control class <"+clazzP+">, using default.",e);
			ccObject=new UDTCongestionControl(this);
		}
		cc=(CongestionControl)ccObject;
		logger.info("Using "+cc.getClass().getName());
		
		int capacity= 2 * getFlowWindowSize();
		//long initialSequenceNum = getInitialSequenceNumber();
		receiveBuffer=new ReceiveBuffer(capacity,0);
		chunksize=getDatagramSize()-24;//need space for the header;
		flowWindow=new FlowWindow(getFlowWindowSize(),chunksize);
		receiver=new UDTReceiver(this);
		sender=new UDTSender(this);
	}
	
	
	public abstract void received(UDTPacket packet, Destination peer);
	
	public final boolean onDataPacketReceived(DataPacket packet) {
		
		if(!receiveBuffer.offer(new AppData((packet.getPacketSequenceNumber()-getInitialSequenceNumber()), packet.getData()))) {
			System.out.println("data packet overload");
			return false;
		}
		
		if(null != sessionHandlers)
			return sessionHandlers.onDataReceive(this, packet);
		return false;
	}
	
	public CongestionControl getCongestionControl() {
		return cc;
	}

	public int getState() {
		return state;
	}

	public void setMode(int mode) {
		this.mode = mode;
	}

	public abstract void setState(int state);
	
	public boolean isReady(){
		return state==ready;
	}

	public boolean isShutdown(){
		return state==shutdown || state==invalid;
	}
	
	public Destination getDestination() {
		return destination;
	}
	
	public int getDatagramSize() {
		return datagramSize;
	}

	public void setDatagramSize(int datagramSize) {
		this.datagramSize = datagramSize;
	}
	
	public int getReceiveBufferSize() {
		return receiveBufferSize;
	}

	public void setReceiveBufferSize(int bufferSize) {
		this.receiveBufferSize = bufferSize;
	}

	public int getFlowWindowSize() {
		return flowWindowSize;
	}

	public void setFlowWindowSize(int flowWindowSize) {
		this.flowWindowSize = flowWindowSize;
	}

	public UDTStatistics getStatistics(){
		return statistics;
	}

	public long getSocketID(){
		return mySocketID;
	}

	
	public synchronized long getInitialSequenceNumber(){
		if(initialSequenceNumber==null){
			initialSequenceNumber=SequenceNumber.random();
		}
		return initialSequenceNumber;
	}
	
	public synchronized void setInitialSequenceNumber(long initialSequenceNumber){
		this.initialSequenceNumber=initialSequenceNumber;
	}

	public DatagramPacket getDatagram(){
		return dgPacket;
	}
	
	public void shutdown()throws IOException{

		if (isReady()&& active==true) 
		{
			Shutdown shutdown = new Shutdown();
			shutdown.setDestinationID(getDestination().getSocketID());
			shutdown.setSession(this);
			endPoint.doSend(shutdown);
			receiver.stop();
			endPoint.stop();
		}
	}

	public UDPEndPoint getEndPoint() {
		return endPoint;
	}
	
	// set to 0 for debug only.
	private long initialSequenceNo = 0; // SequenceNumber.random();
	
	//initial handshake for connect
	protected void sendInitialHandShake()throws IOException{
		ConnectionHandshake handshake = new ConnectionHandshake();
		handshake.setConnectionType(ConnectionHandshake.CONNECTION_TYPE_REGULAR);
		handshake.setSocketType(ConnectionHandshake.SOCKET_TYPE_DGRAM);
		setInitialSequenceNumber(initialSequenceNo);
		handshake.setInitialSeqNo(initialSequenceNo);
		handshake.setPacketSize(getDatagramSize());
		handshake.setSocketID(mySocketID);
		handshake.setMaxFlowWndSize(flowWindowSize);
		handshake.setSession(this);
		handshake.setAddress(endPoint.getLocalAddress());
		logger.info("Sending "+handshake);
		endPoint.doSend(handshake);
	}

	/*
	 * response after the initial connection handshake received:
	 * compute cookie
	 */
	protected void ackInitialHandshake(ConnectionHandshake handshake)throws IOException{
		ConnectionHandshake responseHandshake = new ConnectionHandshake();
		//compare the packet size and choose minimun
		long clientBufferSize=handshake.getPacketSize();
		long myBufferSize=getDatagramSize();
		long bufferSize=Math.min(clientBufferSize, myBufferSize);
		long initialSequenceNumber=handshake.getInitialSeqNo();
		setInitialSequenceNumber(initialSequenceNumber);
		setDatagramSize((int)bufferSize);
		responseHandshake.setPacketSize(bufferSize);
		responseHandshake.setUdtVersion(4);
		responseHandshake.setInitialSeqNo(initialSequenceNumber);
		responseHandshake.setConnectionType(-1);
		responseHandshake.setMaxFlowWndSize(handshake.getMaxFlowWndSize());
		//tell peer what the socket ID on this side is 
		responseHandshake.setSocketID(mySocketID);
		responseHandshake.setDestinationID(this.getDestination().getSocketID());
		responseHandshake.setSession(this);
		sessionCookie=SequenceNumber.random();
		responseHandshake.setCookie(sessionCookie);
		responseHandshake.setAddress(endPoint.getLocalAddress());
		logger.info("Sending reply "+responseHandshake);
		endPoint.doSend(responseHandshake);
	}
	
	//2nd handshake for connect
	protected void sendSecondHandshake()throws IOException{
		ConnectionHandshake handshake = new ConnectionHandshake();
		handshake.setConnectionType(ConnectionHandshake.CONNECTION_TYPE_REGULAR);
		handshake.setSocketType(ConnectionHandshake.SOCKET_TYPE_DGRAM);
		handshake.setInitialSeqNo(initialSequenceNo);
		handshake.setPacketSize(getDatagramSize());
		handshake.setSocketID(mySocketID);
		handshake.setMaxFlowWndSize(flowWindowSize);
		handshake.setSession(this);
		handshake.setCookie(sessionCookie);
		handshake.setAddress(endPoint.getLocalAddress());
		handshake.setDestinationID(getDestination().getSocketID());
		logger.info("Sending confirmation "+handshake);
		endPoint.doSend(handshake);
	}
	
	/**
	 * handle the connection handshake
	 *
	 * @param handshake
	 * @param peer
	 * @throws IOException
	 */
	protected boolean handleSecondHandShake(ConnectionHandshake handshake)throws IOException{
		if(sessionCookie==0){
			ackInitialHandshake(handshake);
			//need one more handshake
			return false;
		}

		long otherCookie=handshake.getCookie();
		if(sessionCookie!=otherCookie){
			setState(invalid);
			throw new IOException("Invalid cookie <"+otherCookie+"> received, my cookie is <"+sessionCookie+">");
		}
		sendFinalHandShake(handshake);
		return true;
	}
	
	private ConnectionHandshake finalConnectionHandshake;
	protected void sendFinalHandShake(ConnectionHandshake handshake)throws IOException{

		if(finalConnectionHandshake==null){
			finalConnectionHandshake= new ConnectionHandshake();
			//compare the packet size and choose minimun
			long clientBufferSize=handshake.getPacketSize();
			long myBufferSize=getDatagramSize();
			long bufferSize=Math.min(clientBufferSize, myBufferSize);
			long initialSequenceNumber=handshake.getInitialSeqNo();
			setInitialSequenceNumber(initialSequenceNumber);
			setDatagramSize((int)bufferSize);
			finalConnectionHandshake.setPacketSize(bufferSize);
			finalConnectionHandshake.setUdtVersion(4);
			finalConnectionHandshake.setInitialSeqNo(initialSequenceNumber);
			finalConnectionHandshake.setConnectionType(-1);
			finalConnectionHandshake.setMaxFlowWndSize(handshake.getMaxFlowWndSize());
			//tell peer what the socket ID on this side is 
			finalConnectionHandshake.setSocketID(mySocketID);
			finalConnectionHandshake.setDestinationID(this.getDestination().getSocketID());
			finalConnectionHandshake.setSession(this);
			finalConnectionHandshake.setCookie(sessionCookie);
			finalConnectionHandshake.setAddress(endPoint.getLocalAddress());
		}
		logger.info("Sending final handshake ack "+finalConnectionHandshake);
		endPoint.doSend(finalConnectionHandshake);
	}

	// Client side handler
	protected void handleConnectionHandshake(ConnectionHandshake hs, Destination peer){
		if (getState()==handshaking) {
			//logger.info("Received initial handshake response from "+peer+"\n"+hs);
			if(hs.getConnectionType()==ConnectionHandshake.CONNECTION_SERVER_ACK){
				try{
					//TODO validate parameters sent by peer
					long peerSocketID=hs.getSocketID();
					sessionCookie=hs.getCookie();
					destination.setSocketID(peerSocketID);
					setState(handshaking+1);
				}catch(Exception ex){
					logger.log(Level.WARNING,"Error creating socket",ex);
					setState(invalid);
				}
				return;
			}
			else{
				logger.info("Unexpected type of handshake packet received");
				setState(invalid);
			}
		}
		else if(getState()==handshaking+1){
			try{
				// logger.info("Received confirmation handshake response from "+peer+"\n"+hs);
				//TODO validate parameters sent by peer
				setState(ready);
				cc.init();
				connected();
			}catch(Exception ex){
				logger.log(Level.WARNING,"Error creating socket",ex);
				setState(invalid);
			}
		}
	}

	/**
	 * Server side handler
	 * reply to a connection handshake message
	 * @param connectionHandshake
	 */
	protected void handleHandShake(ConnectionHandshake connectionHandshake){
		logger.info("Received "+connectionHandshake + " in state <"+getState()+">");
		if(getState()==ready){
			//just send confirmation packet again
			try{
				sendFinalHandShake(connectionHandshake);
			}catch(IOException io){}
			return;
		}

		if (getState()<ready){
			destination.setSocketID(connectionHandshake.getSocketID());

			if(getState()<handshaking){
				setState(handshaking);
			}

			try{
				boolean handShakeComplete=handleSecondHandShake(connectionHandshake);
				if(handShakeComplete){
					logger.info("Client/Server handshake complete!");
					setState(ready);
					cc.init();
				}
			}catch(IOException ex){
				//session invalid
				logger.log(Level.WARNING,"Error processing ConnectionHandshake",ex);
				setState(invalid);
			}
		}
	}

	
	public String toString(){
		StringBuilder sb=new StringBuilder();
		sb.append(super.toString());
		sb.append(" [");
		sb.append("socketID=").append(this.mySocketID);
		sb.append(" ]");
		return sb.toString();
	}
	

	public int write(byte[] b, int len) throws IOException {
		// checkClosed();
		//socket.doWrite(b, 0, len);
		//return len;
		
		
		DataPacket packet = flowWindow.getForProducer();
		if(packet==null)
			return 0;

		try{
			packet.setPacketSequenceNumber(sender.getNextSequenceNumber());
			packet.setSession(this);
			packet.setDestinationID(getDestination().getSocketID());
			int sendlen=Math.min(len,chunksize);
			packet.setData(b);
			packet.setLength(sendlen);
		}finally{
			flowWindow.produce();
		}
		return len;
	}

	public int oldwrite(byte[] b, int len) throws IOException {
		// checkClosed();
		try {
//			socket.doWrite(b, 0, len, 10, TimeUnit.MILLISECONDS);
			
			DataPacket packet=null;
			do{
				packet=flowWindow.getForProducer();
				if(packet==null){
					Thread.sleep(10);
				}
			}while(packet==null);//TODO check timeout...
			try{
				packet.setPacketSequenceNumber(sender.getNextSequenceNumber());
				packet.setSession(this);
				packet.setDestinationID(getDestination().getSocketID());
				//int len=Math.min(bb.remaining(),chunksize);
				//byte[] data=packet.getData();
				//bb.get(data,0,len);
				packet.setData(b);
				//packet.setLength(len);
			}finally{
				flowWindow.produce();
			}
			
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		
		return len;
	}

	/**
	 * will block until the outstanding packets have really been sent out
	 * and acknowledged
	 */
	public void flush() throws InterruptedException{
		// if(!active)return;
		final long seqNo=sender.getCurrentSequenceNumber();
		if(seqNo<0)throw new IllegalStateException();
		while(!sender.isSentOut(seqNo)){
			Thread.sleep(5);
		}
		System.out.println("flash seqNo:" + seqNo);
		if(seqNo>-1){
			//wait until data has been sent out and acknowledged
			while(active && !sender.haveAcknowledgementFor(seqNo)){
				sender.waitForAck(seqNo);
			}
		}
		//TODO need to check if we can pause the sender...
		//sender.pause();
	}

	public UDTReceiver getReceiver() {
		return receiver;
	}

	public UDTSender getSender() {
		return sender;
	}

	
	protected int doSend(UDTPacket packet)throws IOException{
		return endPoint.doSend(packet);
	}
}
