function (operation, expectedUrl, anchor) {
    if (location.href !== expectedUrl || !document.documentElement) return null;

    const notificationAd = "data-panelverse-notification-ad";
    const adSelectors =
        'ins.adsbygoogle,[data-ad-slot],[data-ad-client],.ad-banner,.ad-slot,' +
        '.ad-popup,#ad-popup,.ad-overlay,#ad-overlay,[data-type="_mgwidget"][data-widget-id],' +
        '[' + notificationAd + ']';

    if (operation === 'ads-on' || operation === 'ads-off') {
        const existing = document.getElementById('panelverse-ad-filter');
        if (operation === 'ads-off') {
            if (existing) {
                if (existing.panelverseObserver) existing.panelverseObserver.disconnect();
                existing.remove();
            }
            for (const ad of document.querySelectorAll('[' + notificationAd + ']')) {
                ad.removeAttribute(notificationAd);
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
        const prompt = /click\s+["'“”]?\s*allow\s*["'“”]?\s+if you are not a robot/i;
        const closeLabel = /^(?:close|dismiss|[x×✕✖])$/i;
        let scheduled = false;

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
                        box.setAttribute(notificationAd, '');
                    }
                    break;
                }
            }
        }
        function markPrompts() {
            const body = document.body;
            if (!style.isConnected || !body) return;
            markFloatingAds(body);
            if (!prompt.test(body.textContent)) return;
            const text = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
            for (let node = text.nextNode(); node; node = text.nextNode()) {
                if (!prompt.test(node.nodeValue)) continue;
                for (let box = node.parentElement, depth = 0; box && box !== body && depth < 6;
                    box = box.parentElement, depth++) {
                    const bounds = box.getBoundingClientRect();
                    if (bounds.width < 150 || bounds.height < 80 || bounds.height > innerHeight * 0.9 ||
                        box.querySelector('form, select, iframe, nav, header, footer')) continue;
                    const controls = [...box.querySelectorAll('button, a, [role="button"], [aria-label], [onclick], [data-dismiss], [class*="close"]')];
                    const hasClose = controls.some(control =>
                        closeLabel.test((control.getAttribute('aria-label') || control.textContent).trim()));
                    const hasContinue = controls.some(control => /^continue$/i.test(control.textContent.trim()));
                    if (!hasClose || !hasContinue) continue;
                    box.setAttribute(notificationAd, '');
                    break;
                }
            }
        }

        markPrompts();
        const observer = new MutationObserver(() => {
            if (scheduled) return;
            scheduled = true;
            requestAnimationFrame(() => {
                scheduled = false;
                markPrompts();
            });
        });
        observer.observe(document.documentElement, {childList: true, subtree: true, characterData: true});
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
