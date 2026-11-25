/**
 * 长者档案录入字段校验（`src/lib/patientForm.ts`）单元测试。
 *
 * Runner 与 `age.test.ts` 同一口径（内置 `node:test`）。运行（在 `ui-astro/` 下）：
 *
 *   bun run test:unit:aceso
 *
 * 逐字锁定三条口径：空串一律放行（历史档案与暂无证件/手机的长者要能建档）、
 * 18/15 位身份证号放行、其余格式拦截；手机号只认 11 位。
 */

import { test } from "node:test";
import assert from "node:assert/strict";

import {
	validateIdCardNo,
	validateMobilePhone,
	validatePatientForm,
} from "../src/lib/patientForm.ts";

/** 全部合法的最小表单（各用例在此基础上覆盖单字段） */
const VALID = { name: "张三", idCardNo: "", phone: "", emergencyPhone: "" };

test("姓名必填", () => {
	assert.equal(validatePatientForm({ ...VALID, name: "" }).name, "姓名不能为空");
	assert.equal(validatePatientForm({ ...VALID, name: "   " }).name, "姓名不能为空");
	assert.equal(validatePatientForm(VALID).name, undefined);
});

test("身份证号留空放行", () => {
	assert.equal(validateIdCardNo(""), undefined);
	assert.equal(validateIdCardNo("   "), undefined);
});

test("18 位身份证号（末位 X/x）放行", () => {
	assert.equal(validateIdCardNo("11010519491231002X"), undefined);
	assert.equal(validateIdCardNo("11010519491231002x"), undefined);
	assert.equal(validateIdCardNo("110105194912310021"), undefined);
	assert.equal(validateIdCardNo(" 11010519491231002X "), undefined);
});

test("15 位老身份证号放行", () => {
	assert.equal(validateIdCardNo("110105491231002"), undefined);
});

test("身份证号位数或字符不对一律拦截", () => {
	for (const value of ["11010519491231002", "1101051949123100211", "1101051949123100Y", "11010519491231 002"]) {
		assert.ok(validateIdCardNo(value), `应拦截：${value}`);
	}
});

test("手机号留空放行", () => {
	assert.equal(validateMobilePhone(""), undefined);
	assert.equal(validateMobilePhone("  "), undefined);
});

test("11 位手机号放行", () => {
	for (const value of ["13800000000", "19912345678", " 13800000000 "]) {
		assert.equal(validateMobilePhone(value), undefined, `应放行：${value}`);
	}
});

test("非手机号口径一律拦截", () => {
	for (const value of ["1380000000", "138000000000", "12800000000", "021-88886666", "138 0000 0000", "1380000000a"]) {
		assert.ok(validateMobilePhone(value), `应拦截：${value}`);
	}
});

test("联系人电话与本人电话同口径（11 位手机号）", () => {
	assert.equal(validatePatientForm({ ...VALID, emergencyPhone: "13800000000" }).emergencyPhone, undefined);
	assert.equal(validatePatientForm({ ...VALID, emergencyPhone: "" }).emergencyPhone, undefined);
	assert.ok(validatePatientForm({ ...VALID, emergencyPhone: "0571-88886666" }).emergencyPhone);
	assert.ok(validatePatientForm({ ...VALID, emergencyPhone: "1380000000" }).emergencyPhone);
});

test("汇总：全部合法时返回空对象", () => {
	assert.deepEqual(
		validatePatientForm({
			name: "张三",
			idCardNo: "11010519491231002X",
			phone: "13800000000",
			emergencyPhone: "13900000000",
		}),
		{},
	);
	assert.deepEqual(validatePatientForm(VALID), {});
});

test("汇总：两个电话字段同时非法时各自报错", () => {
	const errors = validatePatientForm({ ...VALID, phone: "123", emergencyPhone: "456" });
	assert.ok(errors.phone);
	assert.ok(errors.emergencyPhone);
});
