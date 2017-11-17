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
import java.net.UnknownHostException;
import java.util.logging.Level;
import java.util.logging.Logger;

import udt.packets.ConnectionHandshake;
import udt.packets.Destination;
import udt.packets.KeepAlive;
import udt.packets.Shutdown;
import udt.packets.DataPacket;

/**
 * server side session in client-server mode
 */
public class ServerSession extends UDTSession {

	private static final Logger logger=Logger.getLogger(ServerSession.class.getName());

	public ServerSession(Destination peer, UDPEndPoint endPoint)throws SocketException,UnknownHostException{
		super("ServerSession localPort="+endPoint.getLocalPort()+" peer="+peer.getAddress()+":"+peer.getPort(),peer, endPoint);
		logger.info("Created "+toString()+" talking to "+peer.getAddress()+":"+peer.getPort());
	}

	public void setState(int state) {
		logger.info(toString()+" connection state CHANGED to <"+state+">");
		this.state = state;
	}
	
	@Override
	public void received(UDTPacket packet, Destination peer){
		if(null == packet) {
			try {
				receiver.receive(null);
			} catch (IOException e) {
				e.printStackTrace();
			}
			return;
		}

		if(packet.isConnectionHandshake()) {
			handleHandShake((ConnectionHandshake)packet);
			return;
		}

		if(packet instanceof KeepAlive) {
			receiver.resetEXPTimer();
			active = true;
			return;
		}

		if (packet instanceof Shutdown) {
			try{
				receiver.stop();
			}catch(IOException ex){
				logger.log(Level.WARNING,"",ex);
			}
			setState(shutdown);
			active = false;
			logger.info("Connection shutdown initiated by peer.");
			return;
		}

		if(getState() == ready) {
			active = true;
			try{
				if(packet.forSender()){
					sender.receive(packet);
				}else{
					receiver.receive(packet);
				}
			}catch(Exception ex){
				//session invalid
				logger.log(Level.SEVERE,"",ex);
				setState(invalid);
			}
			return;
		}
	}

	@Override
	public void connected() {
		System.out.println("server socket connected");
	}
}

