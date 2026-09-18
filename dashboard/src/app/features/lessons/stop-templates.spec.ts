import { describe, expect, it } from 'vitest';
import { STOP_TYPES } from '../../ui/phone-preview';
import { STOP_TEMPLATES, STOP_TEMPLATE_GROUPS, templatesByGroup } from './stop-templates';
import { validateStopValue } from './stop-validator';

/**
 * The "+ Add stop" menu is only useful if every entry posts something the server accepts, so
 * this validates all twenty-two templates against `pnpm schemas`' compiled branches of the very
 * schema `SchemaValidator` uses, and checks the grouping matches `StopTemplates.kt`'s five
 * headings.
 */
describe('stop templates', () => {
  it('has exactly one entry per stop type', () => {
    const types = STOP_TEMPLATES.map((entry) => entry.type);
    expect(new Set(types)).toEqual(new Set(STOP_TYPES));
    expect(types).toHaveLength(STOP_TYPES.length);
  });

  it('groups every type into one of StopTemplates.kt five headings', () => {
    const grouped = STOP_TEMPLATE_GROUPS.flatMap((group) => templatesByGroup(group).map((entry) => entry.type));
    expect(new Set(grouped)).toEqual(new Set(STOP_TYPES));
    expect(grouped).toHaveLength(STOP_TYPES.length);
  });

  it('gives every new stop a fresh id, so two adds never collide', () => {
    const [template] = STOP_TEMPLATES;
    expect(template?.make('math').id).not.toEqual(template?.make('math').id);
  });

  it('uses the subject-appropriate ingredient, as StopTemplates.kt ing(math) does', () => {
    const [template] = STOP_TEMPLATES;
    expect(template?.make('math').ingredient).not.toEqual(template?.make('english').ingredient);
  });

  it.each(STOP_TEMPLATES.map((entry) => entry.type))('%s makes a schema-valid stop', async (type) => {
    const template = STOP_TEMPLATES.find((entry) => entry.type === type);
    const result = await validateStopValue(type, template?.make('english'));
    expect(result.errors).toEqual([]);
    expect(result.valid).toBe(true);
  });
});
