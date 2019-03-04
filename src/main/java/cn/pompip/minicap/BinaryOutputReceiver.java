/*
 * Copyright (c) 2019. Edit By pompip.cn
 */

package cn.pompip.minicap;

import com.android.ddmlib.IShellOutputReceiver;
import cn.pompip.util.Util;

import java.util.Arrays;

/**
 * Created by harry on 2017/4/17.
 */
public class BinaryOutputReceiver implements IShellOutputReceiver {

    byte[] output = new byte[0];

    @Override
    public void addOutput(byte[] bytes, int offest, int len) {
        byte[] b = Arrays.copyOfRange(bytes, offest, offest + len);
        output = Util.mergeArray(output, b);
//        System.out.println(len + ":" + output);
//        System.out.println(new String(bytes, offest, len));
    }

    @Override
    public void flush() {
        System.out.println("flush");
    }

    @Override
    public boolean isCancelled() {
        return false;
    }

    public byte[] getOutput() {
        return output;
    }
}
