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
import java.net.SocketException;
import java.util.logging.Level;
import java.util.logging.Logger;

import udt.packets.ConnectionHandshake;
import udt.packets.Destination;
import udt.packets.Shutdown;
import udt.util.SequenceNumber;

/**
 * Client side of a client-server UDT connection. 
 * Once established, the session provides a valid {@link UDTSocket}.
 */
public abstract class ClientSession extends UDTSession {

	public abstract void connected();
	
	private static final Logger logger=Logger.getLogger(ClientSession.class.getName());

	private UDPEndPoint endPoint;

	long initialSequenceNo=SequenceNumber.random();
	
	public ClientSession(UDPEndPoint endPoint, Destination dest)throws SocketException{
		super("ClientSession localPort="+endPoint.getLocalPort(),dest);
		this.endPoint=endPoint;
		logger.info("Created "+toString());
	}

	/**
	 * send connection handshake until a reply from server is received
	 
	 * @throws InterruptedException
	 * @throws IOException
	 */

	public void connect() throws InterruptedException,IOException{
		int n=0;
		while(getState()!=ready){
			if(getState()==invalid)throw new IOException("Can't connect!");
			if(getState()<=handshaking){
				setState(handshaking);
				sendInitialHandShake();
			}
			else if(getState()==handshaking+1){
				sendSecondHandshake();
			}
			
			if(getState()==invalid)throw new IOException("Can't connect!");
			if(n++ > 1000)throw new IOException("Could not connect to server within the timeout.");
			Thread.sleep(50);
		}
		// ready状态会涉及到UDTSocket的构造，如果不延迟一阵，后期的获取inputStream会出现问题，这里说明顺序是有提升空间的。
		Thread.sleep(1000);
		cc.init();
		logger.info("Connected, "+n+" handshake packets sent");		
		connected();
	}

	@Override
	public void received(UDTPacket packet, Destination peer) {

		lastPacket=packet;

		if (packet.isConnectionHandshake()) {
			ConnectionHandshake hs=(ConnectionHandshake)packet;
			handleConnectionHandshake(hs,peer);
			return;
		}

		if(getState() == ready) {

			if(packet instanceof Shutdown){
				setState(shutdown);
				active=false;
				logger.info("Connection shutdown initiated by the other side.");
				return;
			}
			active = true;
			try{
				if(packet.forSender()){
					socket.getSender().receive(lastPacket);
				}else{
					socket.getReceiver().receive(lastPacket);	
				}
			}catch(Exception ex){
				//session is invalid
				logger.log(Level.SEVERE,"Error in "+toString(),ex);
				setState(invalid);
			}
			return;
		}
	}

	protected void handleConnectionHandshake(ConnectionHandshake hs, Destination peer){

		if (getState()==handshaking) {
			logger.info("Received initial handshake response from "+peer+"\n"+hs);
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
				logger.info("Received confirmation handshake response from "+peer+"\n"+hs);
				//TODO validate parameters sent by peer
				setState(ready);
				socket=new UDTSocket(endPoint,this);
			}catch(Exception ex){
				logger.log(Level.WARNING,"Error creating socket",ex);
				setState(invalid);
			}
		}
	}

	//initial handshake for connect
	protected void sendInitialHandShake()throws IOException{
		ConnectionHandshake handshake = new ConnectionHandshake();
		handshake.setConnectionType(ConnectionHandshake.CONNECTION_TYPE_REGULAR);
		handshake.setSocketType(ConnectionHandshake.SOCKET_TYPE_DGRAM);
//		long initialSequenceNo=SequenceNumber.random();
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


	public UDTPacket getLastPkt(){
		return lastPacket;
	}


}
