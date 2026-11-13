function (operation, expectedUrl, anchor) {
    if (location.href !== expectedUrl || !document.documentElement) return null;

    if ((operation === 'comic-issues' || operation === 'comic-pages') &&
        (location.hostname === 'readcomicsonline.ru' || location.hostname === 'www.readcomicsonline.ru')) {
        if (operation === 'comic-issues') {
            const root = location.pathname.replace(/\/$/, '') + '/';
            return [...document.querySelectorAll('a[href]')]
                .filter(link => {
                    const path = link.pathname.replace(/\/$/, '');
                    return link.origin === location.origin && path.startsWith(root) &&
                        !path.slice(root.length).includes('/');
                })
                .slice(0, 2000)
                .map(link => ({
                    url: link.href.slice(0, 4096),
                    title: (link.querySelector('.text-brand-400') || link).textContent.trim().slice(0, 140)
                }));
        }

        const attributes = ['data-background-image', 'data-cfsrc', 'data-lazy-src', 'data-src', 'data-original', 'src'];
        const images = [...document.querySelectorAll('#reader-all img, #all img')];
        if (!images.length || images.length > 1000) return null;
        const issuePath = location.pathname.split('/').filter(Boolean);
        const pageRoute = issuePath.length === 4 && /^[1-9][0-9]*$/.test(issuePath[3]);
        const pageTotal = (document.body?.innerText || '').match(/\bPage\s+[0-9]+\s*\/\s*([0-9]+)\b/i);
        if ((pageRoute && images.length === 1) || (pageTotal && images.length < Number(pageTotal[1]))) return null;
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
        'ins.adsbygoogle,[data-ad-slot],[data-ad-client],[data-ad-unit],[data-ad-zone],' +
        '.ad-banner,.ad-slot,.ad-container,.advertisement,.ad-popup,#ad-popup,.ad-overlay,#ad-overlay,' +
        '[data-type="_mgwidget"][data-widget-id],a[rel~="sponsored"],' +
        '[' + hiddenAd + ']';

    if (operation === 'ads-on' || operation === 'ads-off') {
        if (!['mangakatana.com', 'www.mangakatana.com', 'readcomicsonline.ru', 'www.readcomicsonline.ru']
            .includes(location.hostname)) return null;
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
            watchReaderAds(style);
        }
        return null;
    }

    function watchReaderAds(style) {
        const prompt = /click\s+["'“”]?\s*allow\s*["'“”]?\s+if\s+you\s+are\s+not\s+a\s+robot/i;
        const ageAd = /confirm\s+that\s+you['’]re\s+over\s+18\s+and\s+then\s+click\s+["'“”]?allow["'“”]?\s+to\s+view\s+the\s+site/i;
        const fakeCaptcha = /\be[\s–-]*captcha\b/i;
        const closeLabel = /^(?:close|dismiss|[x×✕✖])$/i;
        const adBrand = /\b(?:adskeeper|inspiredot|interesting for you)\b/i;
        const notificationTime = /\b(?:now|\d+ (?:seconds?|minutes?) ago)\b/i;
        const comicProvider = /^(?:www\.)?readcomicsonline\.ru$/.test(location.hostname);
        const sponsorPrompt = /\bvisit\s+(?:https?:\/\/)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?:\/[^\s]*)?\s+to learn more\b/i;
        const vpnPrompt = /\bvpn\s+toggle\s*[-–—]?\s*recommended\b|\bthe best in online security\b/i;
        const chapterPath = /^\/(?:manga|comic)\/[^/]+\/[^/]+\/?$/.test(location.pathname);
        const readerImages = '#imgs [id^="page"] img,#reader-all img,#all img';
        const pageControls = 'form,select,nav,header,footer';
        const protectedContent = 'img, picture, video, canvas, form, select, nav, header, footer';
        let pendingScan = null;

        function containsReaderPage(element) {
            return element.matches(readerImages) || element.querySelector(readerImages) !== null;
        }

        function hideAd(element) {
            if (!containsReaderPage(element)) element.setAttribute(hiddenAd, '');
        }

        function hasCloseControl(element) {
            return [...element.querySelectorAll('button,a,[role="button"],[aria-label],[title],[onclick],[class*="close"]')]
                .some(control => {
                    const label = (control.getAttribute('aria-label') ||
                        control.getAttribute('title') || control.textContent).trim();
                    return closeLabel.test(label) || (control.matches('[class*="close"]') && !label);
                });
        }

        function hideComicPromotion(seed) {
            let card = null;
            for (let box = seed, depth = 0; box && box !== document.body && depth < 9;
                box = box.parentElement, depth++) {
                if (box.matches(pageControls) || box.querySelector(pageControls + ',iframe') || containsReaderPage(box)) break;
                const bounds = box.getBoundingClientRect();
                if (bounds.height > innerHeight * 0.75) break;
                if (bounds.width < innerWidth * 0.4 || bounds.height < 32) continue;
                const text = (box.innerText || box.textContent).replace(/\s+/g, ' ').trim();
                if (text.length > 500) break;
                const position = getComputedStyle(box).position;
                const floating = position === 'fixed' || position === 'sticky' || position === 'absolute';
                const media = box.querySelector('img,picture,video,canvas,svg,[style*="background-image"]');
                if (vpnPrompt.test(text) && floating) {
                    card = box;
                    break;
                }
                if (!sponsorPrompt.test(text)) continue;
                if (floating && (media || hasCloseControl(box))) {
                    card = box;
                    break;
                }
                if (!card && media) card = box;
            }
            if (card) hideAd(card);
        }

        function markInlineBanners(body) {
            if (!chapterPath) return;
            for (const media of body.querySelectorAll('a[href] img,a[href] video,a[href] canvas')) {
                const link = media.closest('a[href]');
                if (link.closest(pageControls) || containsReaderPage(link)) continue;
                let destination;
                try { destination = new URL(link.href, location.href); } catch (_) { continue; }
                if (!['https:', 'http:', 'javascript:'].includes(destination.protocol) ||
                    destination.hostname.replace(/^www\./, '') === location.hostname.replace(/^www\./, '')) continue;
                const bounds = media.getBoundingClientRect();
                if (bounds.width >= innerWidth * 0.4 && bounds.height >= 64) hideAd(link);
            }
        }

        function hideNotification(seed, checked) {
            for (let box = seed, depth = 0; box && box !== document.body && depth < 9;
                box = box.parentElement, depth++) {
                if (checked.has(box)) break;
                checked.add(box);
                if (box.matches(pageControls) || box.querySelector(pageControls) || containsReaderPage(box)) break;
                const bounds = box.getBoundingClientRect();
                if (bounds.width < 140 || bounds.height < 60 || bounds.height > innerHeight * 0.75) continue;
                const position = getComputedStyle(box).position;
                if (position !== 'fixed' && position !== 'absolute') continue;
                const text = box.innerText || box.textContent;
                const notification = notificationTime.test(text) && box.querySelector('img,picture,video,[style*="background"]');
                if ((adBrand.test(text) || notification) && hasCloseControl(box)) {
                    hideAd(box);
                    break;
                }
            }
        }

        function collapseSlot(label) {
            if (label === document.body || label.querySelector(protectedContent)) return;
            let slot = label;
            for (let box = label.parentElement, depth = 0; box && box !== document.body && depth < 5;
                box = box.parentElement, depth++) {
                const text = box.textContent.replace(/[\s|]+/g, ' ').trim();
                const inlineSlot = chapterPath && !box.querySelector(pageControls) && !containsReaderPage(box);
                if (!/^remove ad$/i.test(text) ||
                    (box.querySelector(protectedContent) && !inlineSlot) ||
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

                if (brandedPrompt || (bounds.height <= innerHeight && hasCloseControl(box) && /\bcontinue\b/i.test(text))) {
                    overlay = box;
                    break;
                }
            }
            if (overlay) hideAd(overlay);
        }

        function markFloatingAds(body) {
            for (const artwork of body.querySelectorAll('img')) {
                if (artwork.matches(readerImages)) continue;
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
                    if (hasCloseControl(box)) hideAd(box);
                    break;
                }
            }
        }
        function scanAds() {
            const body = document.body;
            if (!style.isConnected || !body) return;
            markFloatingAds(body);
            markInlineBanners(body);
            const checked = new Set();
            const checkedNotifications = new Set();
            const text = document.createTreeWalker(body, NodeFilter.SHOW_TEXT);
            for (let node = text.nextNode(); node; node = text.nextNode()) {
                const label = node.parentElement;
                if (!label || label.closest('script, style, template, [' + hiddenAd + ']')) continue;
                if (/^remove\s+ad$/i.test(label.textContent.trim())) collapseSlot(label);
                else if (/\b(?:click|allow|robot)\b/i.test(node.nodeValue)) hidePrompt(label, checked);
                if (adBrand.test(node.nodeValue) || notificationTime.test(node.nodeValue)) {
                    hideNotification(label, checkedNotifications);
                }
                if (comicProvider && /\b(?:visit|learn\s+more|vpn|online\s+security)\b/i.test(node.nodeValue)) {
                    hideComicPromotion(label);
                }
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
            attributeFilter: ['style', 'class', 'hidden', 'src', 'href', 'rel']
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
