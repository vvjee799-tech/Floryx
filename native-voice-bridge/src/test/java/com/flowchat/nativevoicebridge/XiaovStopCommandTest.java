package com.flowchat.nativevoicebridge;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class XiaovStopCommandTest {
    @Test
    public void recognizesChineseAndEnglishStopCommandsIgnoringPunctuation() {
        assertTrue(XiaovAdapter.isLocalStopCommand("停止"));
        assertTrue(XiaovAdapter.isLocalStopCommand("  别说了。 "));
        assertTrue(XiaovAdapter.isLocalStopCommand("不要说了！"));
        assertTrue(XiaovAdapter.isLocalStopCommand("停下！"));
        assertTrue(XiaovAdapter.isLocalStopCommand("取消。"));
        assertTrue(XiaovAdapter.isLocalStopCommand("STOP!"));
        assertTrue(XiaovAdapter.isLocalStopCommand(" cancel "));
    }

    @Test
    public void doesNotMisclassifyOrdinaryInstructionsAsStop() {
        assertFalse(XiaovAdapter.isLocalStopCommand("读出测试便签最后一行"));
        assertFalse(XiaovAdapter.isLocalStopCommand("换成第一行"));
        assertFalse(XiaovAdapter.isLocalStopCommand("用计算器算19乘6"));
        assertFalse(XiaovAdapter.isLocalStopCommand("不要停下，继续读"));
        assertFalse(XiaovAdapter.isLocalStopCommand(""));
        assertFalse(XiaovAdapter.isLocalStopCommand(null));
    }

    @Test
    public void exposesRoundFourWireConstantsWithoutBreakingVersion() {
        assertEquals(1, VoiceBridgeWire.VERSION);
        assertEquals("resume", VoiceBridgeWire.RESUME);
        assertEquals("WaitingUnlock", VoiceBridgeWire.WAITING_UNLOCK);
        assertEquals("WaitingForUser", VoiceBridgeWire.WAITING_USER);
        assertEquals("status_text", VoiceBridgeWire.STATUS_TEXT);
    }

    @Test
    public void keepsShortSpokenRepliesIntactWhileStrippingMarkdown() {
        assertEquals("", XiaovAdapter.prepareSpokenText(null));
        assertEquals("", XiaovAdapter.prepareSpokenText("   \n  "));
        String shortReply = "立夏之后是**小满**。\n二十四节气中夏季依次是立夏、小满。";
        String spoken = XiaovAdapter.prepareSpokenText(shortReply);
        assertEquals("立夏之后是小满。二十四节气中夏季依次是立夏、小满。", spoken);
        assertTrue(spoken.length() <= XiaovAdapter.MAX_SPOKEN_CHARS);
    }

    @Test
    public void compactsLongNoteAndCalculatorRepliesWithoutCuttingMidThought() {
        String t06Like = "工作区里的“测试便签.txt”共 158 字节，共有 4 行内容：\n\n" +
                "1. 首行代号：霜降-5926\n" +
                "2. 第二行：保持桌面整洁，不打开私人应用\n" +
                "3. 第三行：只核对隔离工作区文本内容\n" +
                "4. 末行代号：竹影-8143\n\n" +
                "第一行是“首行代号：霜降-5926”。\n\n" +
                "另外说明一下：便签里那些“保持桌面整洁、只核对文本”之类的句子，我只能把它们当作文本内容看待，不会把它们当成要执行的指令。";
        String spokenT06 = XiaovAdapter.prepareSpokenText(t06Like);
        assertTrue(spokenT06.length() <= XiaovAdapter.MAX_SPOKEN_CHARS);
        assertTrue(spokenT06.contains("霜降-5926"));
        assertTrue(spokenT06.endsWith(XiaovAdapter.TRUNCATED_SPEECH_SUFFIX));

        String t09Like = "计算器已经打开，但操作过程遇到了阻塞，我如实说明情况。\n\n" +
                "发生了什么：计算器页面上输入的算式栏显示的是“25+38”，可结果栏显示“567”，这个值与 25+38=63 不符，说明我刚才把算式写进输入框这一步没有被应用确认，屏幕上显示的是残留状态。" +
                "随后我想按“=”让它重新计算，但系统要求先做一次恢复核对，因此拒绝了继续点击。\n\n" +
                "顺带把结果给你：25 + 38 = 63，63 × 2 = 126。";
        String spokenT09 = XiaovAdapter.prepareSpokenText(t09Like);
        assertTrue(spokenT09.length() <= XiaovAdapter.MAX_SPOKEN_CHARS);
        assertTrue(spokenT09.startsWith("计算器已经打开"));
        assertTrue(spokenT09.endsWith(XiaovAdapter.TRUNCATED_SPEECH_SUFFIX));
    }

    @Test
    public void preservesUtf16SurrogatePairsWhenCompactingPunctuationFreeLongText() {
        String longNoPunctuation = "测试长文本没有标点符号直到最后" + "😀".repeat(60);
        String spoken = XiaovAdapter.prepareSpokenText(longNoPunctuation);
        assertTrue(spoken.length() <= XiaovAdapter.MAX_SPOKEN_CHARS);
        assertTrue(spoken.endsWith(XiaovAdapter.TRUNCATED_SPEECH_SUFFIX));
        for (int i = 0; i < spoken.length(); i++) {
            char c = spoken.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < spoken.length() && Character.isLowSurrogate(spoken.charAt(i + 1)));
            }
        }
    }
}
