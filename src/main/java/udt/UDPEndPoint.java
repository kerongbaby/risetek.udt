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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import udt.packets.ConnectionHandshake;
import udt.packets.Destination;
import udt.packets.PacketFactory;
import udt.util.UDTThreadFactory;
import udt.util.Util;

/**
 * the UDPEndpoint takes care of sending and receiving UDP network packets,
 * dispatching them to the correct {@link UDTSession}
 */
public class UDPEndPoint {

	private static final Logger logger=Logger.getLogger(UDPEndPoint.class.getName());

//	private final int port;

//	private final DatagramSocket dgSocket;
	private final DatagramChannel dgChannel;
	private final Selector selector;

	//active sessions keyed by socket ID
	private final Map<Long,UDTSession>sessions=new ConcurrentHashMap<Long, UDTSession>();

	//last received packet
	private UDTPacket lastPacket;

	private final Map<Destination,UDTSession> sessionsBeingConnected=Collections.synchronizedMap(new HashMap<Destination,UDTSession>());

	//if the endpoint is configured for a server socket,
	//this queue is used to handoff new UDTSessions to the application
	private final SynchronousQueue<UDTSession> sessionHandoff=new SynchronousQueue<UDTSession>();

	private boolean serverSocketMode=false;

	//has the endpoint been stopped?
	private volatile boolean stopped=false;

	public static final int DATAGRAM_SIZE=1400;

	/**
	 * bind to any local port on the given host address
	 * @param localAddress
	 * @throws SocketException
	 * @throws UnknownHostException
	 */
	public UDPEndPoint(InetAddress localAddress)throws IOException {
		this(localAddress,0);
	}

	/**
	 * Bind to the given address and port
	 * @param localAddress
	 * @param localPort - the port to bind to. If the port is zero, the system will pick an ephemeral port.
	 * @throws IOException  
	 */
	public UDPEndPoint(InetAddress localAddress, int localPort)throws IOException {
		selector = Selector.open();

		dgChannel = DatagramChannel.open();
		if(localPort != 0)
			dgChannel.socket().bind(new InetSocketAddress(localPort));
		
		//set a time out to avoid blocking in doReceive()
		dgChannel.socket().setSoTimeout(100000);
		//buffer size
		dgChannel.socket().setReceiveBufferSize(128*1024);
		dgChannel.socket().setReuseAddress(true);
		
		dgChannel.configureBlocking(false);
		dgChannel.register(selector, SelectionKey.OP_READ);
	}

	/**
	 * bind to an ephemeral port on the default network interface on the machine
	 * 
	 * @throws SocketException
	 * @throws UnknownHostException
	 */
	public UDPEndPoint()throws IOException {
		this(null,0);
	}

	/**
	 * start the endpoint. If the serverSocketModeEnabled flag is <code>true</code>,
	 * a new connection can be handed off to an application. The application needs to
	 * call #accept() to get the socket
	 * @param serverSocketModeEnabled
	 */
	public void start(boolean serverSocketModeEnabled){
		serverSocketMode=serverSocketModeEnabled;
		//start receive thread
		Runnable receive=new Runnable(){
			public void run(){
				try{
					doReceive();
				}catch(Exception ex){
					logger.log(Level.WARNING,"",ex);
				}
			}
		};
		Thread t=UDTThreadFactory.get().newThread(receive);
		t.setName("UDPEndpoint-"+t.getName());
		t.setDaemon(true);
		t.start();
		logger.info("UDTEndpoint started.");
	}

	public void stop() throws IOException {
		stopped=true;
		//dgSocket.close();
		dgChannel.close();
	}

	/**
	 * @return the port which this client is bound to
	 */
	public int getLocalPort() {
		//return this.dgSocket.getLocalPort();
		return dgChannel.socket().getLocalPort();
	}
	/**
	 * @return Gets the local address to which the socket is bound
	 */
	public InetAddress getLocalAddress(){
		//return this.dgSocket.getLocalAddress();
		return dgChannel.socket().getLocalAddress();
	}

	UDTPacket getLastPacket(){
		return lastPacket;
	}

	public void addSession(Long destinationID,UDTSession session){
		logger.info("Storing session <"+destinationID+">");
		sessions.put(destinationID, session);
	}

	public UDTSession getSession(Long destinationID){
		return sessions.get(destinationID);
	}

	public Collection<UDTSession> getSessions(){
		return sessions.values();
	}

	/**
	 * wait the given time for a new connection
	 * @param timeout - the time to wait
	 * @param unit - the {@link TimeUnit}
	 * @return a new {@link UDTSession}
	 * @throws InterruptedException
	 */
	protected UDTSession accept(long timeout, TimeUnit unit)throws InterruptedException{
		return sessionHandoff.poll(timeout, unit);
	}


	// final DatagramPacket dp= new DatagramPacket(new byte[DATAGRAM_SIZE],DATAGRAM_SIZE);
	private final ByteBuffer dpbuffer = ByteBuffer.allocate(DATAGRAM_SIZE);

	/**
	 * single receive, run in the receiverThread, see {@link #start()}
	 * <ul>
	 * <li>Receives UDP packets from the network</li> 
	 * <li>Converts them to UDT packets</li>
	 * <li>dispatches the UDT packets according to their destination ID.</li>
	 * </ul> 
	 * @throws IOException
	 */
	protected void doReceive()throws IOException{
		while(!stopped){
			if(0 == selector.select(Util.getSYNTime()/100) ) {
				for(UDTSession session:sessions.values())
					session.received(null, null);
				continue;
			}

			// Get iterator on set of keys with I/O to process
			Iterator<SelectionKey> keyIter = selector.selectedKeys().iterator();
			while (keyIter.hasNext()) {
				SelectionKey key = keyIter.next(); // Key is bit mask

				// Client socket channel has pending data?
				if (key.isReadable()) {
					try{
						//will block until a packet is received or timeout has expired
						dpbuffer.clear();
						InetSocketAddress from = (InetSocketAddress)dgChannel.receive(dpbuffer);
						Destination peer=new Destination(from.getAddress(), from.getPort());
						int l=dpbuffer.position();
						dpbuffer.flip();
						UDTPacket packet=PacketFactory.createPacket(dpbuffer.array(),l);
						lastPacket=packet;

						long dest=packet.getDestinationID();
						UDTSession session=sessions.get(dest);
						if(session!=null){
							//dispatch to existing session
							session.received(packet,peer);
						}
						else if(packet.isConnectionHandshake()){
							System.out.println("isConnectionHandshake");
							Destination p=new Destination(peer.getAddress(),peer.getPort());
							session=sessionsBeingConnected.get(peer);
							long destID=packet.getDestinationID();
							if(session!=null && session.getSocketID()==destID){
								//confirmation handshake
								sessionsBeingConnected.remove(p);
								addSession(destID, session);
							}
							else if(session==null){
								session=new ServerSession(peer,this);
								sessionsBeingConnected.put(p,session);
								sessions.put(session.getSocketID(), session);
								if(serverSocketMode){
									logger.fine("Pooling new request.");
									sessionHandoff.put(session);
									session.connected();
									logger.fine("Request taken for processing.");
								}
							}
							else {
								throw new IOException("dest ID sent by client does not match: " + session.getSocketID() + " : " + destID);
							}
							Long peerSocketID=((ConnectionHandshake)packet).getSocketID();
							peer.setSocketID(peerSocketID);
							session.received(packet,peer);
						}
						else{
							logger.warning("Unknown session <"+dest+"> requested from <"+peer+"> packet type "+packet.getClass().getName());
						}
					}catch(SocketException ex){
						logger.log(Level.INFO, "SocketException: "+ex.getMessage());
					}catch(SocketTimeoutException ste){
						//can safely ignore... we will retry until the endpoint is stopped
					}catch(Exception ex){
						logger.log(Level.WARNING, "Got: "+ex.getMessage(),ex);
					}
					// Register write with the selector
					if(!stopped)
						key.interestOps(SelectionKey.OP_READ);
				}

			}
			keyIter.remove();
		}
	}

	/**
	 * called when a "connection handshake" packet was received and no 
	 * matching session yet exists
	 * 
	 * @param packet
	 * @param peer
	 * @throws IOException
	 * @throws InterruptedException
	 */
	protected int doSend(UDTPacket packet)throws IOException{
		byte[]data=packet.getEncoded();
		DatagramPacket dgp = packet.getSession().getDatagram();
		ByteBuffer bb = ByteBuffer.wrap(data);
		return dgChannel.send(bb, dgp.getSocketAddress());
	}

	public String toString(){
		return  "UDPEndpoint port="+dgChannel.socket().getLocalPort();
	}

	public void sendRaw(DatagramPacket p)throws IOException{
		dgChannel.socket().send(p);
	}

}
