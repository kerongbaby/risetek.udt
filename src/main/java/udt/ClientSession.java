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

	public ClientSession(UDPEndPoint endPoint, Destination dest)throws SocketException{
		super("ClientSession localPort="+endPoint.getLocalPort(),dest, endPoint);
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
}
