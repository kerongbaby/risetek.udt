package udt;

public class AppData implements Comparable<AppData>{
	final long sequenceNumber;
	final byte[] data;
	public AppData(long sequenceNumber, byte[]data){
		this.sequenceNumber=sequenceNumber;
		this.data=data;
	}

	public int compareTo(AppData o) {
		return (int)(sequenceNumber-o.sequenceNumber);
	}

	public String toString(){
		return sequenceNumber+"["+data.length+"]";
	}

	public long getSequenceNumber(){
		return sequenceNumber;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
		+ (int) (sequenceNumber ^ (sequenceNumber >>> 32));
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AppData other = (AppData) obj;
		if (sequenceNumber != other.sequenceNumber)
			return false;
		return true;
	}
}
