function (operation, expectedUrl, anchor) {
    if (location.href !== expectedUrl || !document.documentElement) return null;

    if ((operation === 'comic-issues' || operation === 'comic-pages') &&
        (location.hostname === 'readcomicsonline.ru' || location.hostname === 'www.readcomicsonline.ru')) {
        if (operation === 'comic-issues') {
            const root = location.pathname.replace(/\/$/, '') + '/';
            return [...document.querySelectorAll('a[href]')]
                .filter(link => link.origin === location.origin && link.pathname.startsWith(root) &&
                    !link.pathname.slice(root.length).includes('/') && link.pathname !== root)
                .slice(0, 2000)
                .map(link => ({
                    url: link.href.slice(0, 4096),
                    title: (link.querySelector('.text-brand-400') || link).textContent.trim().slice(0, 140)
                }));
        }

        const attributes = ['data-background-image', 'data-cfsrc', 'data-lazy-src', 'data-src', 'data-original', 'src'];
        const images = [...document.querySelectorAll('#reader-all img, #all img')];
        if (!images.length || images.length > 1000) return null;
        const pages = [];
        for (const image of images) {
            const source = attributes.map(name => image.getAttribute(name)).find(value => value && value.trim());
            if (!source || source.length > 4096) return null;
            try {
                const url = new URL(source, location.href);
                if (url.protocol !== 'https:') return null;
                pages.push(url.href);
            } catch (_) {
                return null;
            }
        }
        return pages;
    }

    const hiddenAd = "data-panelverse-hidden-ad";
    const adSelectors =
        'ins.adsbygoogle,[data-ad-slot],[data-ad-client],.ad-banner,.ad-slot,' +
        '.ad-popup,#ad-popup,.ad-overlay,#ad-overlay,[data-type="_mgwidget"][data-widget-id],' +
        '[' + hiddenAd + ']';

    if (operation === 'ads-on' || operation === 'ads-off') {
        const existing = document.getElementById('panelverse-ad-filter');
        if (operation === 'ads-off') {
            if (existing) {
                if (existing.panelverseObserver) existing.panelverseObserver.disconnect();
                existing.remove();
            }
            for (const ad of document.querySelectorAll('[' + hiddenAd + ']')) {
                ad.removeAttribute(hiddenAd);
            }
        } else if (!existing) {
            const style = document.createElement('style');
            style.id = 'panelverse-ad-filter';
            style.textContent = adSelectors + '{display:none!important}';
            (document.head || document.documentElement).appendChild(style);
            if (location.hostname === 'mangakatana.com' || location.hostname === 'www.mangakatana.com') {
                watchMangaAds(style);
            }
        }
        return null;
    }

    function watchMangaAds(style) {
        const prompt = /click\s+["'“”]?\s*allow\s*["'“”]?\s+if\s+you\s+are\s+not\s+a\s+robot/i;
        const ageAd = /confirm\s+that\s+you['’]re\s+over\s+18\s+and\s+then\s+click\s+["'“”]?allow["'“”]?\s+to\s+view\s+the\s+site/i;
        const fakeCaptcha = /\be[\s–-]*captcha\b/i;
        const closeLabel = /^(?:close|dismiss|[x×✕✖])$/i;
        const protectedContent = 'img, picture, video, canvas, form, select, nav, header, footer';
        let pendingScan = null;

        function hideAd(element) {
            element.setAttribute(hiddenAd, '');
        }

        function collapseSlot(label) {
            if (label === document.body || label.querySelector(protectedContent)) return;
            let slot = label;
            for (let box = label.parentElement, depth = 0; box && box !== document.body && depth < 5;
                box = box.parentElement, depth++) {
                const text = box.textContent.replace(/[\s|]+/g, ' ').trim();
                if (!/^remove ad$/i.test(text) ||
                    box.querySelector(protectedContent) ||
                    box.getBoundingClientRect().height > innerHeight * 0.6) break;
                slot = box;
            }
            hideAd(slot);
        }

        function hidePrompt(seed, checked) {
            let overlay = null;
            for (let box = seed, depth = 0; box && box !== document.body && depth < 9;
                box = box.parentElement, depth++) {
                if (checked.has(box)) break;
                checked.add(box);
                const bounds = box.getBoundingClientRect();
                if (bounds.width < 150 || bounds.height < 80) continue;
                if (box.querySelector('form, select, nav, header, footer')) break;
                const text = box.innerText || box.textContent;
                const position = getComputedStyle(box).position;
                const floating = position === 'fixed' || position === 'absolute';
                if (floating && bounds.height <= innerHeight && ageAd.test(text)) {
                    overlay = box;
                    break;
                }
                if (!prompt.test(text)) continue;

                const brandedPrompt = fakeCaptcha.test(text) && /\bcontinue\b/i.test(text);
                if (!overlay && brandedPrompt && bounds.height <= innerHeight) overlay = box;
                if (!floating) continue;

                const close = [...box.querySelectorAll('button, a, [role="button"], [aria-label], [title], [onclick], [class*="close"]')]
                    .some(control => closeLabel.test((control.getAttribute('aria-label') ||
                        control.getAttribute('title') || control.textContent).trim()) ||
                        (control.matches('[class*="close"]') && !control.textContent.trim()));
                if (brandedPrompt || (bounds.height <= innerHeight && close && /\bcontinue\b/i.test(text))) {
                    overlay = box;
                    break;
                }
            }
            if (overlay) hideAd(overlay);
        }

        function markFloatingAds(body) {
            for (const artwork of body.querySelectorAll('img')) {
                const artworkBounds = artwork.getBoundingClientRect();
                if (artworkBounds.width < 80 || artworkBounds.width > innerWidth * 0.65 ||
                    artworkBounds.top < -8 || artworkBounds.top > innerHeight * 0.3 ||
                    artworkBounds.right < innerWidth * 0.7) continue;
                const ratio = artworkBounds.height / artworkBounds.width;
                if (ratio < 0.85 || ratio > 1.15) continue;

                let round = false;
                for (let box = artwork, depth = 0; box && box !== body && depth < 5;
                    box = box.parentElement, depth++) {
                    const appearance = getComputedStyle(box);
                    const radius = appearance.borderTopLeftRadius;
                    round = round || (radius.endsWith('%') ? parseFloat(radius) >= 40 :
                        parseFloat(radius) >= artworkBounds.width * 0.4);
                    if (!round || appearance.position !== 'fixed') continue;
                    const bounds = box.getBoundingClientRect();
                    if (bounds.width > innerWidth * 0.75 || bounds.height > innerHeight * 0.6 ||
                        box.querySelector('form, select, iframe, nav, header, footer')) break;
                    const controls = [...box.querySelectorAll('button, a, [aria-label], [onclick], [class*="close"]')];
                    if (controls.some(control =>
                        closeLabel.test((control.getAttribute('aria-label') || control.textContent).trim()))) {
                        hideAd(box);
                    }
                    break;
                }
            }
        }
        function scanAds() {
            const body = document.body;
            if (!style.isConnected || !body) return;
            markFloatingAds(body);
            const checked = new Set();
            const text = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
            for (let node = text.nextNode(); node; node = text.nextNode()) {
                const label = node.parentElement;
                if (!label || label.closest('script, style, template, [' + hiddenAd + ']')) continue;
                if (/^remove\s+ad$/i.test(label.textContent.trim())) collapseSlot(label);
                else if (/\b(?:click|allow|robot)\b/i.test(node.nodeValue)) hidePrompt(label, checked);
            }
        }

        scanAds();
        const observer = new MutationObserver(() => {
            if (pendingScan !== null) return;
            pendingScan = setTimeout(() => {
                pendingScan = null;
                scanAds();
            }, 100);
        });
        observer.observe(document.documentElement, {
            childList: true, subtree: true, characterData: true, attributes: true,
            attributeFilter: ['style', 'class', 'hidden']
        });
        style.panelverseObserver = observer;
    }

    function imageKey(img) {
        const source = img.getAttribute('data-src') || img.getAttribute('data-original') ||
            img.currentSrc || img.src;
        if (!source) return null;
        try {
            const url = new URL(source, location.href);
            return url.protocol === 'https:' && url.pathname.length <= 1024 ? url.pathname : null;
        } catch (_) {
            return null;
        }
    }

    function readingImage(img, bounds) {
        return bounds.width >= innerWidth * 0.55 && bounds.height >= innerHeight * 0.25 &&
            bounds.height >= bounds.width * 0.75 &&
            !img.closest('header,nav,footer,aside,' + adSelectors);
    }

    if (operation === 'capture') {
        const focus = innerHeight * 0.45;
        let nearest = null;
        let distance = Infinity;
        for (const image of document.images) {
            const bounds = image.getBoundingClientRect();
            if (bounds.bottom <= 0 || bounds.top >= innerHeight || !readingImage(image, bounds)) continue;
            const key = imageKey(image);
            if (!key) continue;
            const score = Math.max(bounds.top - focus, focus - bounds.bottom, 0);
            if (score >= distance) continue;
            distance = score;
            nearest = {key, offset: Math.round(Math.min(1, Math.max(0, -bounds.top / bounds.height)) * 10000)};
        }
        return nearest;
    }

    if (operation === 'restore' && anchor) {
        for (const image of document.images) {
            if (imageKey(image) !== anchor.key) continue;
            const bounds = image.getBoundingClientRect();
            if (!readingImage(image, bounds)) continue;
            const delta = bounds.top + bounds.height * anchor.offset / 10000;
            window.scrollBy({top: delta, behavior: 'instant'});
            const position = image.getBoundingClientRect();
            return image.complete && image.naturalWidth > 0 && position.bottom > 0 &&
                position.top < innerHeight;
        }
    }
    return false;
}
