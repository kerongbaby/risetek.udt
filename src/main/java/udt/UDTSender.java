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
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import udt.packets.Acknowledgement;
import udt.packets.Acknowledgment2;
import udt.packets.DataPacket;
import udt.packets.KeepAlive;
import udt.packets.NegativeAcknowledgement;
import udt.sender.SenderLossList;
import udt.util.MeanThroughput;
import udt.util.MeanValue;
import udt.util.SequenceNumber;
import udt.util.UDTStatistics;

/**
 * sender part of a UDT entity
 * 
 * @see UDTReceiver
 */
public class UDTSender {

	private static final Logger logger = Logger.getLogger(UDTSender.class.getName());

	private final UDPEndPoint endpoint;

	private final UDTSession _session;

	private final UDTStatistics statistics;

	// senderLossList stores the sequence numbers of lost packets
	// fed back by the receiver through NAK pakets
	private final SenderLossList senderLossList;

	// sendBuffer stores the sent data packets and their sequence numbers
	private final Map<Long, byte[]> sendBuffer;

	// protects against races when reading/writing to the sendBuffer
	private final Object sendLock = new Object();

	// number of unacknowledged data packets
	private final AtomicInteger unacknowledged = new AtomicInteger(0);

	// for generating data packet sequence numbers
	private volatile long currentSequenceNumber = 0;

	// the largest data packet sequence number that has actually been sent out
	private volatile long largestSentSequenceNumber = -1;

	// last acknowledge number, initialised to the initial sequence number
	private volatile long lastAckSequenceNumber;

	// used by the sender to wait for an ACK
	private final ReentrantLock ackLock = new ReentrantLock();
	private final Condition ackCondition = ackLock.newCondition();

	private final boolean storeStatistics;
	private Timer timer = new Timer(false);

	public UDTSender(UDTSession session) {
		// if(!session.isReady())throw new IllegalStateException("UDTSession is
		// not ready.");
		_session = session;
		endpoint = session.getEndPoint();
		statistics = session.getStatistics();
		senderLossList = new SenderLossList();
		sendBuffer = new ConcurrentHashMap<Long, byte[]>(session.getFlowWindowSize(), 0.75f, 2);
		lastAckSequenceNumber = 0;// session.getInitialSequenceNumber();
		currentSequenceNumber = lastAckSequenceNumber - 1;// session.getInitialSequenceNumber()-1;
		storeStatistics = Boolean.getBoolean("udt.sender.storeStatistics");
		initMetrics();

	}

	public void start() {
		timer.schedule(new SenderTask(), 0);
	}
	
	public int sendData() {
		System.out.println("simulater send");
		return 0;
	}
	
	public void sendTask() {
		int totalSend = 0;
		
		long interval = (long) _session.getCongestionControl().getSendInterval();
		long timer_period = interval;

		// TODO: 发送间隔是按照10ms为单位的，但是java无法提供如此细致的分辨率，所以实际的发送
		// 需要做些调整才对。
		int index =0;
		try {

			for(index=0;index<256;index++)
			{
				// if the sender's loss list is not empty
				Long entry = senderLossList.getFirstEntry();
				if (entry != null) {

					int len = handleRetransmit(entry);
					if(len <= 0) {
						// TODO: 发送失败，我们应该修改拥塞数据？
						System.out.println("lost missing? " + entry);
						// timer_period += 20;
						break;
					}

					totalSend += len;
					continue;
				}

				// if the number of unacknowledged data packets does not
				// exceed the congestion
				// and the flow window sizes, pack a new packet
				int unAcknowledged = unacknowledged.get();

				if (unAcknowledged >= _session.getCongestionControl().getCongestionWindowSize()) {
					statistics.incNumberOfCCWindowExceededEvents();
					// waitForAck();
					// System.out.println("hold:" + unAcknowledged + " / " + _session.getCongestionControl().getCongestionWindowSize());
					// TODO: calculate for wait timer period by unAcknowledged!
					timer_period += unAcknowledged;
					// timer.schedule(new SenderTask(), timer_period);
					//return;
					break;
				} else if (unAcknowledged < _session.getFlowWindowSize()) {
					// check for application data
					boolean havemore = _session.onDataRequest();
					DataPacket dp = _session.flowWindow.consumeData();
					if (dp != null) {
						int len;
						if((len = send(_session, dp)) <= 0) {
							System.out.format("send failed number: %d total send: %d\r\n", dp.getPacketSequenceNumber(), index);
							senderLossList.insert(dp.getPacketSequenceNumber());
							// TODO: 发送失败，我们应该修改拥塞数据？
							timer_period += 20 + unAcknowledged;
							break;
						}
						totalSend += len;
						largestSentSequenceNumber = dp.getPacketSequenceNumber();
					} else {
						statistics.incNumberOfMissingDataEvents();
						if(!havemore && sendBuffer.isEmpty()) {
							System.out.println("no datas to send, stop sender");
							return;
						}
						timer_period = 20; // unAcknowledged;
						break;
					}
				} else {
					// TODO: 这个时候没有受限于拥塞，但是受限于流控
					// 发送间隔显然与 unAcknowledged 数目有关
					// 是否也与 LinkCapacity 相关呢？如果相关，应该怎么带入？
					// timer_period = 20; //unAcknowledged / 10;
					break;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		// if(0!=index)		System.out.format("period %d %d index: %d\r\n", timer_period, index);

		if(!_session.isShutdown() && null != timer) {
			timer.schedule(new SenderTask(), timer_period/10);
		}
	}
	
	private class SenderTask extends TimerTask {
		@Override
		public void run() {
			sendTask();
		}
	}

	private MeanValue dgSendTime;
	private MeanValue dgSendInterval;
	private MeanThroughput throughput;

	private void initMetrics() {
		if (!storeStatistics)
			return;
		dgSendTime = new MeanValue("SENDER: Datagram send time");
		statistics.addMetric(dgSendTime);
		dgSendInterval = new MeanValue("SENDER: Datagram send interval");
		statistics.addMetric(dgSendInterval);
		throughput = new MeanThroughput("SENDER: Throughput", _session.getDatagramSize());
		statistics.addMetric(throughput);
	}

	/**
	 * sends the given data packet, storing the relevant information
	 */
	private int send(UDTSession session, DataPacket p) throws IOException {
		int val = 0;
		synchronized (sendLock) {
			if (storeStatistics) {
				dgSendInterval.end();
				dgSendTime.begin();
			}
			val = endpoint.doSend(session, p);

			if (storeStatistics) {
				dgSendTime.end();
				dgSendInterval.begin();
				throughput.end();
				throughput.begin();
			}
			// store data for potential retransmit
/*			
			int l = p.getLength();
			byte[] data = new byte[l];
			System.arraycopy(p.getData(), 0, data, 0, l);
*/
//			sendBuffer.put(p.getPacketSequenceNumber(), data);
			sendBuffer.put(p.getPacketSequenceNumber(), p.getData());
			unacknowledged.incrementAndGet();
			statistics.incNumberOfSentDataPackets();
		}
		return val;
	}

	// receive a packet from server from the peer
	protected void receive(UDTPacket p) throws IOException {
		if (p instanceof Acknowledgement) {
			Acknowledgement acknowledgement = (Acknowledgement) p;
			onAcknowledge(acknowledgement);
			_session.getReceiver().resetEXPTimer();
		} else if (p instanceof NegativeAcknowledgement) {
			NegativeAcknowledgement nak = (NegativeAcknowledgement) p;
			onNAKPacketReceived(nak);
			_session.getReceiver().resetEXPTimer();
		} else if (p instanceof KeepAlive) {
			_session.getReceiver().resetEXPCount();
		}
	}

	private void onAcknowledge(Acknowledgement acknowledgement) throws IOException {
		ackLock.lock();
		ackCondition.signal();
		ackLock.unlock();

		CongestionControl cc = _session.getCongestionControl();
		long rtt = acknowledgement.getRoundTripTime();
		if (rtt > 0) {
			long rttVar = acknowledgement.getRoundTripTimeVar();
			cc.setRTT(rtt, rttVar);
			statistics.setRTT(rtt, rttVar);
		}
		long rate = acknowledgement.getPacketReceiveRate();
		if (rate > 0) {
			long linkCapacity = acknowledgement.getEstimatedLinkCapacity();
			cc.updatePacketArrivalRate(rate, linkCapacity);
			statistics.setPacketArrivalRate(cc.getPacketArrivalRate(), cc.getEstimatedLinkCapacity());
		}

		long ackNumber = acknowledgement.getAckNumber();
		cc.onACK(ackNumber);
		statistics.setCongestionWindowSize((long) cc.getCongestionWindowSize());
		// need to remove all sequence numbers up the ack number from the
		// sendBuffer
		boolean removed = false;
		// System.out.format("ACK from: %d to %d\r\n", lastAckSequenceNumber, ackNumber);
		for (long s = lastAckSequenceNumber; s < ackNumber; s++) {
			synchronized (sendLock) {
				removed = sendBuffer.remove(s) != null;
				senderLossList.remove(s);
			}
			if (removed) {
				unacknowledged.decrementAndGet();
			}
		}
		lastAckSequenceNumber = Math.max(lastAckSequenceNumber, ackNumber);
		// send ACK2 packet to the receiver
		sendAck2(ackNumber);
		
		int unAcknowledged = unacknowledged.get();
		if(0 == unAcknowledged)
			_session.onSendEmpty();
		
		statistics.incNumberOfACKReceived();
		if (storeStatistics)
			statistics.storeParameters();
	}

	/**
	 * procedure when a NAK is received (spec. p 14)
	 * 
	 * @param nak
	 */
	private void onNAKPacketReceived(NegativeAcknowledgement nak) {
		for (Integer i : nak.getDecodedLossInfo()) {
			senderLossList.insert(Long.valueOf(i));
		}
		_session.getCongestionControl().onLoss(nak.getDecodedLossInfo());
		statistics.incNumberOfNAKReceived();

		System.out.println("NAK for " + nak.getDecodedLossInfo().size() + " packets lost, " + "set send period to "
				+ _session.getCongestionControl().getSendInterval());

		if (logger.isLoggable(Level.FINER)) {
			logger.finer("NAK for " + nak.getDecodedLossInfo().size() + " packets lost, " + "set send period to "
					+ _session.getCongestionControl().getSendInterval());
		}
	}

	// send single keep alive packet -> move to socket!
	protected void sendKeepAlive() throws Exception {
		KeepAlive keepAlive = new KeepAlive();
		// TODO
		keepAlive.setSession(_session);
		endpoint.doSend(_session, keepAlive);
	}

	private void sendAck2(long ackSequenceNumber) throws IOException {
		Acknowledgment2 ackOfAckPkt = new Acknowledgment2();
		ackOfAckPkt.setAckSequenceNumber(ackSequenceNumber);
		ackOfAckPkt.setSession(_session);
		ackOfAckPkt.setDestinationID(_session.getDestination().getSocketID());
		endpoint.doSend(_session, ackOfAckPkt);
	}

	private final DataPacket retransmit = new DataPacket();

	/**
	 * re-transmit an entry from the sender loss list
	 * 
	 * @param entry
	 */
	private int handleRetransmit(Long seqNumber) {
		int len = 0;
		try {
			// retransmit the packet and remove it from the list
			byte[] data = sendBuffer.get(seqNumber);
			assert(data != null);

			retransmit.setPacketSequenceNumber(seqNumber);
			retransmit.setSession(_session);
			retransmit.setDestinationID(_session.getDestination().getSocketID());
			retransmit.setData(data);
			len = endpoint.doSend(_session, retransmit);
			statistics.incNumberOfRetransmittedDataPackets();
		} catch (Exception e) {
			logger.log(Level.WARNING, "", e);
		}
		return len;
	}

	/**
	 * for processing EXP event (see spec. p 13)
	 */
	protected void putUnacknowledgedPacketsIntoLossList() {
		synchronized (sendLock) {
			for (Long l : sendBuffer.keySet()) {
				senderLossList.insert(l);
			}
		}
	}

	/**
	 * the next sequence number for data packets. The initial sequence number is
	 * "0"
	 */
	public long getNextSequenceNumber() {
		currentSequenceNumber = SequenceNumber.increment(currentSequenceNumber);
		return currentSequenceNumber;
	}

	public long getCurrentSequenceNumber() {
		return currentSequenceNumber;
	}

	/**
	 * returns the largest sequence number sent so far
	 */
	public long getLargestSentSequenceNumber() {
		return largestSentSequenceNumber;
	}

	/**
	 * returns the last Ack. sequence number
	 */
	public long getLastAckSequenceNumber() {
		return lastAckSequenceNumber;
	}

	boolean haveAcknowledgementFor(long sequenceNumber) {
		return SequenceNumber.compare(sequenceNumber, lastAckSequenceNumber) <= 0;
	}

	boolean isSentOut(long sequenceNumber) {
		return SequenceNumber.compare(largestSentSequenceNumber, sequenceNumber) >= 0;
	}

	boolean haveLostPackets() {
		return !senderLossList.isEmpty();
	}

	/**
	 * wait until the given sequence number has been acknowledged
	 * 
	 * @throws InterruptedException
	 */
	public void waitForAck(long sequenceNumber) throws InterruptedException {
		while (!_session.isShutdown() && !haveAcknowledgementFor(sequenceNumber)) {
			ackLock.lock();
			try {
				ackCondition.await(100, TimeUnit.MICROSECONDS);
			} finally {
				ackLock.unlock();
			}
		}
	}

	public void waitForAck(long sequenceNumber, int timeout) throws InterruptedException {
		while (!_session.isShutdown() && !haveAcknowledgementFor(sequenceNumber)) {
			ackLock.lock();
			try {
				ackCondition.await(timeout, TimeUnit.MILLISECONDS);
			} finally {
				ackLock.unlock();
			}
		}
	}

	/**
	 * wait for the next acknowledge
	 * 
	 * @throws InterruptedException
	 */
	public void waitForAck() throws InterruptedException {
		ackLock.lock();
		try {
			ackCondition.await(200, TimeUnit.MICROSECONDS);
		} finally {
			ackLock.unlock();
		}
	}

	public void stop() {
		timer.cancel();
		timer = null;
	}
}
