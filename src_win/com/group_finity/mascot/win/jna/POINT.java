package com.group_finity.mascot.win.jna;

import com.sun.jna.Structure;
import java.util.Arrays;
import java.util.List;

public class POINT extends Structure {

	public int x;
	public int y;

	public static class ByValue extends POINT implements Structure.ByValue { }

	@Override
	protected List<String> getFieldOrder( )
	{
		return Arrays.asList( "x", "y" );
	}
}
