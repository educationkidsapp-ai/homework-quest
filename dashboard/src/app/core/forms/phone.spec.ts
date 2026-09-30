import { describe, expect, it } from 'vitest';
import { isPhone, normalisePhone, phoneErrorKey } from './phone';

/**
 * The cases are `quest.server.platform.Phones`'s own, so the day the server's rule moves this spec
 * is what says the dashboard's copy has stopped agreeing with it.
 */
describe('phone', () => {
  it('drops the separators a person types', () => {
    expect(normalisePhone('050 100-2030')).toBe('0501002030');
    expect(normalisePhone(' (050) 100.2030 ')).toBe('0501002030');
  });

  it('turns a leading 00 into a +, and keeps a + that is already there', () => {
    expect(normalisePhone('00201001002030')).toBe('+201001002030');
    expect(normalisePhone('+20 100 100 2030')).toBe('+201001002030');
  });

  it('refuses a + that is not at the front — two numbers run together, or a typo', () => {
    expect(isPhone('050+1002030')).toBe(false);
  });

  it('refuses anything that is neither a digit nor a separator', () => {
    expect(isPhone('050/1002030')).toBe(false);
    expect(isPhone('call me')).toBe(false);
  });

  it('holds E.164 to seven digits at the least and fifteen at the most', () => {
    expect(isPhone('123456')).toBe(false);
    expect(isPhone('1234567')).toBe(true);
    expect(isPhone('123456789012345')).toBe(true);
    expect(isPhone('1234567890123456')).toBe(false);
  });

  /** "She has not given us one" is a state every directory screen prints. */
  it('accepts a blank value, and normalises it to null', () => {
    expect(isPhone('')).toBe(true);
    expect(isPhone('   ')).toBe(true);
    expect(normalisePhone('  ')).toBeNull();
  });

  it('answers a key rather than a sentence, so the caller translates it', () => {
    expect(phoneErrorKey('0501002030')).toBeNull();
    expect(phoneErrorKey('nope')).toBe('form.phone.invalid');
  });
});
