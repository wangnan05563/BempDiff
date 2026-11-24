package com.bempdiff.test;

import com.bempdiff.diff.OfficeTextDiff;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** T06230 安全加固：XXE 恶意 XML 必须被拒绝，且不能读取本地文件/展开外部实体。 */
public final class SecurityHardeningTest {

    public void testOfficeParseXmlRejectsDoctypeAndExternalEntity() throws Exception {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///C:/Windows/win.ini\">]>"
                + "<root>&xxe;</root>";
        Method parse = OfficeTextDiff.class.getDeclaredMethod("parseXml", byte[].class);
        parse.setAccessible(true);
        try {
            parse.invoke(null, xxe.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Asserts.fail("带 DOCTYPE/外部实体的 XML 应被拒绝");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Asserts.assertNotNull("应抛出可读异常", cause);
            Asserts.assertTrue("异常应说明 XML 解析失败: " + cause.getMessage(),
                    cause instanceof IllegalArgumentException
                            && String.valueOf(cause.getMessage()).contains("XML 解析失败"));
        }
    }

    public void testOfficeParseXmlStillParsesSafeOfficeXml() throws Exception {
        String safe = "<?xml version=\"1.0\"?><worksheet><sheetData><row r=\"1\"/></sheetData></worksheet>";
        Method parse = OfficeTextDiff.class.getDeclaredMethod("parseXml", byte[].class);
        parse.setAccessible(true);
        Object doc = parse.invoke(null, safe.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Asserts.assertNotNull("正常 Office XML 应能解析", doc);
    }
}
