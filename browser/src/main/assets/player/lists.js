/*
 * Menu delle liste di TV e Radio. L'ultima voce porta a dove si aggiungono: sul telefono l'app apre le Impostazioni al
 * punto giusto, in auto risponde di no e qui compare l'avviso di farlo dal telefono.
 */
(function (root) {
    'use strict';

    const MANAGE = '#manage'; // nessuna lista ha questo id
    const NOTICE_MS = 6000;

    root.createSourcePicker = function (select, options) {
        let sources = options.sources || [];
        let selected = '';
        let noticeTimer = 0;

        function render() {
            select.textContent = '';
            sources.concat([{ id: MANAGE, name: options.addLabel }]).forEach((source) => {
                const option = select.ownerDocument.createElement('option');
                option.value = source.id;
                option.textContent = source.name;
                select.appendChild(option);
            });
            select.value = selected;
        }

        function showPhoneOnly() {
            const box = options.messageBox;
            const text = options.phoneOnlyText;
            box.textContent = text;
            box.hidden = false;
            clearTimeout(noticeTimer);
            noticeTimer = setTimeout(() => {
                if (box.textContent !== text) return;
                box.textContent = '';
                box.hidden = true;
            }, NOTICE_MS);
        }

        function manage() {
            const bridge = root.AndroidBridge;
            const opened = !!(bridge && bridge.openMediaLists && bridge.openMediaLists(options.section));
            if (!opened) showPhoneOnly();
        }

        select.addEventListener('change', () => {
            if (select.value === MANAGE) {
                select.value = selected;
                manage();
                return;
            }
            selected = select.value;
            options.onChange(selected);
        });

        render();

        return {
            list: () => sources,
            has: (id) => sources.some((source) => source.id === id),
            select(id) {
                selected = id;
                select.value = id;
            },
            // Liste cambiate nelle Impostazioni: se quella in uso non c'è più si passa alla prima
            update(list) {
                sources = list;
                const gone = !sources.some((source) => source.id === selected);
                if (gone && sources.length) selected = sources[0].id;
                render();
                if (gone && sources.length) options.onChange(selected);
            }
        };
    };
})(typeof window !== 'undefined' ? window : globalThis);
