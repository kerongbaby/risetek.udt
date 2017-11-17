package udt.util;

import java.util.concurrent.atomic.AtomicInteger;

import udt.AppData;

/**
 * 
 * The receive buffer stores data chunks to be read by the application
 *
 * @author schuller
 */
public class ReceiveBuffer {

	private final AppData[]buffer;

	//the head of the buffer: contains the next chunk to be read by the application, 
	//i.e. the one with the lowest sequence number
	private volatile int readPosition=0;

	//the lowest sequence number stored in this buffer
	private final long initialSequenceNumber = 0;

	//the highest sequence number already read by the application
	private long highestReadSequenceNumber;

	//number of chunks
	private final AtomicInteger numValidChunks=new AtomicInteger(0);

	//the size of the buffer
	private final int size;

	public ReceiveBuffer(int size){
		this.size=size;
		this.buffer=new AppData[size];
		highestReadSequenceNumber=SequenceNumber.decrement(initialSequenceNumber);
	}

	public boolean offer(AppData data){
		if(numValidChunks.get()==size) {
			return false;
		}
		long seq=data.getSequenceNumber();
		//if already have this chunk, discard it
		if(SequenceNumber.compare(seq, highestReadSequenceNumber)<=0){
			return true;
		}
		//else compute insert position
		int offset=(int)SequenceNumber.seqOffset(initialSequenceNumber, seq);
		int insert=offset% size;
		buffer[insert]=data;
		numValidChunks.incrementAndGet();
		return true;
	}

	/**
	 * return a data chunk, guaranteed to be in-order. 
	 */
	public AppData poll(){
		if(numValidChunks.get()==0){
			return null;
		}
		AppData r=buffer[readPosition];
		if(r!=null){
			long thisSeq=r.getSequenceNumber();
			if(1==SequenceNumber.seqOffset(highestReadSequenceNumber,thisSeq)){
				buffer[readPosition]=null;
				readPosition++;
				if(readPosition==size)readPosition=0;
				numValidChunks.decrementAndGet();
				highestReadSequenceNumber=thisSeq;
			}
			else return null;
		}
		return r;
	}
}
