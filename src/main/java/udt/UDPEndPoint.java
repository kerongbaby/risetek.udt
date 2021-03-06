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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
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
public abstract class UDPEndPoint implements Runnable {
	private static final Logger logger=Logger.getLogger(UDPEndPoint.class.getName());

	private final DatagramChannel dgChannel;
	private final Selector selector;

	//active sessions keyed by socket ID
	private final Map<Long,UDTSession>sessions=new ConcurrentHashMap<Long, UDTSession>();

	private final Map<Destination,UDTSession> sessionsBeingConnected=Collections.synchronizedMap(new HashMap<Destination,UDTSession>());

	//if the endpoint is configured for a server socket,
	//this queue is used to handoff new UDTSessions to the application
	private final SynchronousQueue<UDTSession> sessionHandoff=new SynchronousQueue<UDTSession>();

	//has the endpoint been stopped?
	private volatile boolean stopped=false;

	public static final int DATAGRAM_SIZE=1400;

	public abstract UDTSession onSessionCreate(Destination peer, UDPEndPoint endPoint) throws SocketException, IOException;

	/**
	 * bind to any local port on the given host address
	 * @param localAddress
	 * @throws SocketException
	 * @throws UnknownHostException
	 */
	public UDPEndPoint(InetAddress localAddress)throws IOException {
		this(localAddress,0);
	}

	
	public UDTSession createClientSession(Destination destination) throws SocketException, IOException {
		UDTSession creator = onSessionCreate(destination, this);
		addSession(creator.getSocketID(), creator);
		return creator;
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
		// 20181007 Send Buffer 超过256*1024似乎没有作用。
		dgChannel.socket().setSendBufferSize(256*1024);
		// 接收缓存增加有明显作用，性能更平滑。如果偏小，包到达率影响严重。
		dgChannel.socket().setReceiveBufferSize(512*1024);
		dgChannel.socket().setReuseAddress(true);
		
		dgChannel.configureBlocking(false);

		//start receive thread
		Thread t=UDTThreadFactory.get().newThread(this);
		t.setName("UDPEndpoint-"+t.getName());
		t.setDaemon(true);
		t.start();
		logger.info("UDTEndpoint started.");
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
	
	public void run(){
		try{
			doReceive();
		}catch(Exception ex){
			logger.log(Level.WARNING,"",ex);
		}
	}

	public void stop() throws IOException {
		stopped=true;
		dgChannel.close();
	}

	/**
	 * @return the port which this client is bound to
	 */
	public int getLocalPort() {
		return dgChannel.socket().getLocalPort();
	}
	/**
	 * @return Gets the local address to which the socket is bound
	 */
	public InetAddress getLocalAddress(){
		return dgChannel.socket().getLocalAddress();
	}

	public void addSession(Long destinationID,UDTSession session){
		logger.info("Storing session <"+destinationID+">");
		sessions.put(destinationID, session);
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

	private final ByteBuffer dpbuffer = ByteBuffer.allocate(DATAGRAM_SIZE);

	public void needtoSend() throws IOException {
		// dgChannel.register(selector, SelectionKey.OP_WRITE);
	}
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
		dgChannel.register(selector, SelectionKey.OP_READ);

		while(!stopped){
			if(0 == selector.select(Util.getSYNTime()/100) ) {
				for(UDTSession session:sessions.values()) {
					session.received(null, null);
					if(session.getState() == UDTSession.shutdown) {
						System.out.println("remove shutdown session.");
						session.onSessionEnd();
						sessions.remove(session.getSocketID());
					}
				}
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

						long dest=packet.getDestinationID();
						UDTSession session=sessions.get(dest);
						if(session!=null){
							//dispatch to existing session
							session.received(packet,peer);

							if(session.getState() == UDTSession.shutdown) {
								session.onSessionEnd();
								sessions.remove(session.getSocketID());
							}
						}
						else if(packet.isConnectionHandshake()){
							Destination p=new Destination(peer.getAddress(),peer.getPort());
							session=sessionsBeingConnected.get(peer);
							long destID=packet.getDestinationID();
							if(session!=null && session.getSocketID()==destID){
								//confirmation handshake
								sessionsBeingConnected.remove(p);
								addSession(destID, session);
							}
							else if(session==null){
								session=onSessionCreate(peer,this);
								sessionsBeingConnected.put(p,session);
								sessions.put(session.getSocketID(), session);
								session.onSessionPrepare();
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
				} else if(key.isWritable()) {
					int sented = 0;
					for(UDTSession session:sessions.values()) {
						sented += session.sender.sendData();
					}
					/*
					if(!stopped && sented > 0)
						key.interestOps(SelectionKey.OP_WRITE);
						*/
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
	protected int doSend(UDTSession session, UDTPacket packet)throws IOException{
		byte[]data=packet.getEncoded();
		ByteBuffer bb = ByteBuffer.wrap(data);
		return dgChannel.send(bb, session.getTargetAddress());
	}

}
