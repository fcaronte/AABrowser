// Menu delle liste di TV e Radio (assets/player/lists.js), con un menu finto al posto del DOM.
// node --test browser/src/test/js/lists.test.js
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
require('../../main/assets/player/lists.js');

function fakeSelect() {
    const select = {
        options: [],
        value: '',
        listeners: {},
        ownerDocument: { createElement: () => ({ value: '', textContent: '' }) },
        set textContent(_) {
            select.options = [];
        },
        appendChild(option) {
            select.options.push(option);
        },
        addEventListener(type, listener) {
            select.listeners[type] = listener;
        },
        choose(value) {
            select.value = value;
            select.listeners.change();
        },
    };
    return select;
}

function picker(sources, onChange = () => {}) {
    const select = fakeSelect();
    const box = { textContent: '', hidden: true };
    const value = globalThis.createSourcePicker(select, {
        section: 'radio',
        sources,
        addLabel: '+ Add',
        phoneOnlyText: 'On the phone',
        messageBox: box,
        onChange,
    });
    return { select, box, value };
}

const lists = [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }];

test.afterEach(() => {
    delete globalThis.AndroidBridge;
});

test('the lists come first, the add entry last', () => {
    const { select } = picker(lists);
    assert.deepEqual(select.options.map((o) => o.textContent), ['A', 'B', '+ Add']);
});

test('choosing a list reports it', () => {
    const chosen = [];
    const { select, value } = picker(lists, (id) => chosen.push(id));
    value.select('a');
    select.choose('b');
    assert.deepEqual(chosen, ['b']);
});

test('on the phone the add entry asks the app and keeps the current list', () => {
    const asked = [];
    globalThis.AndroidBridge = { openMediaLists: (section) => { asked.push(section); return true; } };
    const chosen = [];
    const { select, box, value } = picker(lists, (id) => chosen.push(id));
    value.select('b');
    select.choose(select.options[2].value);
    assert.deepEqual(asked, ['radio']);
    assert.equal(select.value, 'b');
    assert.deepEqual(chosen, []);
    assert.equal(box.hidden, true);
});

test('in the car the add entry says to use the phone', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    globalThis.AndroidBridge = { openMediaLists: () => false };
    const { select, box, value } = picker(lists);
    value.select('a');
    select.choose(select.options[2].value);
    assert.equal(select.value, 'a');
    assert.equal(box.textContent, 'On the phone');
    assert.equal(box.hidden, false);
    t.mock.timers.tick(6000);
    assert.equal(box.hidden, true);
});

test('a page without the bridge method (Wide) says to use the phone too', () => {
    globalThis.AndroidBridge = {};
    const { select, box, value } = picker(lists);
    value.select('a');
    select.choose(select.options[2].value);
    assert.equal(box.textContent, 'On the phone');
});

test('the notice does not hide a newer message', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    const { select, box, value } = picker(lists);
    value.select('a');
    select.choose(select.options[2].value);
    box.textContent = 'Loading';
    t.mock.timers.tick(6000);
    assert.equal(box.textContent, 'Loading');
    assert.equal(box.hidden, false);
});

test('a new list appears and the current one stays', () => {
    const chosen = [];
    const { select, value } = picker(lists, (id) => chosen.push(id));
    value.select('b');
    value.update(lists.concat({ id: 'c', name: 'C' }));
    assert.deepEqual(select.options.map((o) => o.textContent), ['A', 'B', 'C', '+ Add']);
    assert.equal(select.value, 'b');
    assert.deepEqual(chosen, []);
    assert.equal(value.has('c'), true);
});

test('when the current list is removed the first one takes over', () => {
    const chosen = [];
    const { select, value } = picker(lists, (id) => chosen.push(id));
    value.select('b');
    value.update([lists[0]]);
    assert.equal(select.value, 'a');
    assert.deepEqual(chosen, ['a']);
});
