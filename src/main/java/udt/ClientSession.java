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
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import udt.packets.ConnectionHandshake;
import udt.packets.Destination;
import udt.packets.Shutdown;

/**
 * Client side of a client-server UDT connection. 
 * Once established, the session provides a valid.
 */
public abstract class ClientSession extends UDTSession {

	private static final Logger logger=Logger.getLogger(ClientSession.class.getName());

	public ClientSession(UDPEndPoint endPoint, Destination dest)throws SocketException{
		super("ClientSession localPort="+endPoint.getLocalPort(),dest, endPoint);
		logger.info("Created "+toString());
	}

	public void setState(int state) {
			logger.info(toString()+" connection state CHANGED to <"+state+">");
			this.state = state;
			if(state == (handshaking+1)) {
				try {
					System.out.println("sendSecondHandshake");
					sendSecondHandshake();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
	}
	/**
	 * send connection handshake until a reply from server is received
	 
	 * @throws InterruptedException
	 * @throws IOException
	 */
	Timer timer = new Timer();
	public void connect() throws InterruptedException,IOException{
		if(getState() == ready)
			return;
		if(getState() == invalid)
			throw new IOException("invalid connection");
			
		timer.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				if( getState() == ready ) {
					System.out.println("session connected, stop timer");
					timer.cancel();
				}

				if(getState()<=handshaking){
					setState(handshaking);
					try {
						System.out.println("sendInitialHandShake");
						sendInitialHandShake();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			
			}}, 0, 100);
	}

	@Override
	public void received(UDTPacket packet, Destination peer) {
		if(null == packet) {
			try {
				receiver.receive(null);
			} catch (IOException e) {
				e.printStackTrace();
			}
			return;
		}
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
					sender.receive(packet);
				}else{
					receiver.receive(packet);
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
