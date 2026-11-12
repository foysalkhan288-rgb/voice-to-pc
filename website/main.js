(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  // ----- Nav: scrolled state + mobile menu -----
  const nav = document.querySelector(".nav");
  const burger = $("burger");
  const navLinks = $("navLinks");
  const setScrolled = () => nav.classList.toggle("scrolled", scrollY > 8);
  addEventListener("scroll", setScrolled, { passive: true });
  setScrolled();

  const closeMenu = () => {
    navLinks.classList.remove("open");
    document.body.classList.remove("menu-open");
    burger.setAttribute("aria-expanded", "false");
    burger.setAttribute("aria-label", "Open menu");
  };
  burger.addEventListener("click", () => {
    const open = navLinks.classList.toggle("open");
    document.body.classList.toggle("menu-open", open);
    burger.setAttribute("aria-expanded", String(open));
    burger.setAttribute("aria-label", open ? "Close menu" : "Open menu");
  });
  navLinks.addEventListener("click", (e) => {
    if (e.target.closest("a")) closeMenu();
  });
  addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeMenu();
  });

  // ----- Scrollspy -----
  const spyLinks = [...navLinks.querySelectorAll('a[href^="#"]')].filter(
    (a) => !a.classList.contains("btn")
  );
  const byId = new Map(spyLinks.map((a) => [a.getAttribute("href").slice(1), a]));
  const sections = [...byId.keys()]
    .map((id) => document.getElementById(id))
    .filter(Boolean);
  if ("IntersectionObserver" in window) {
    const spy = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          if (!en.isIntersecting) continue;
          spyLinks.forEach((a) => a.classList.remove("active"));
          byId.get(en.target.id)?.classList.add("active");
        }
      },
      { rootMargin: "-40% 0px -55% 0px" }
    );
    sections.forEach((s) => spy.observe(s));
  }

  // ----- Reveal on scroll -----
  const revealEls = document.querySelectorAll(".reveal");
  if (reduced || !("IntersectionObserver" in window)) {
    revealEls.forEach((el) => el.classList.add("in"));
  } else {
    const ro = new IntersectionObserver(
      (entries) => {
        for (const en of entries) {
          if (en.isIntersecting) {
            en.target.classList.add("in");
            ro.unobserve(en.target);
          }
        }
      },
      { threshold: 0, rootMargin: "0px 0px -8% 0px" }
    );
    revealEls.forEach((el) => ro.observe(el));
  }

  // ----- FAQ accordion (one open at a time) -----
  const faqItems = [...document.querySelectorAll(".faq-item")];
  faqItems.forEach((item) => {
    const btn = item.querySelector("button");
    const panel = item.querySelector(".faq-panel");
    btn.addEventListener("click", () => {
      const wasOpen = item.classList.contains("open");
      faqItems.forEach((other) => {
        other.classList.remove("open");
        other.querySelector("button").setAttribute("aria-expanded", "false");
        other.querySelector(".faq-panel").style.maxHeight = "0px";
      });
      if (!wasOpen) {
        item.classList.add("open");
        btn.setAttribute("aria-expanded", "true");
        panel.style.maxHeight = panel.scrollHeight + "px";
      }
    });
  });

  // ----- Listening demo: wave + typers + toggle -----
  const listenCard = $("listenCard");
  const listenDot = $("listenDot");
  const statusDot = $("statusDot");
  const listenTitle = $("listenTitle");
  const listenStatus = $("listenStatus");
  const wave = $("wave");
  let listening = true;

  const BAR_COUNT = 56;
  const bars = [];
  for (let i = 0; i < BAR_COUNT; i++) {
    const b = document.createElement("i");
    wave.appendChild(b);
    bars.push(b);
  }
  const flattenWave = () => bars.forEach((b) => (b.style.height = "12px"));

  if (!reduced) {
    let lastFrame = 0;
    const drawWave = (t) => {
      requestAnimationFrame(drawWave);
      if (t - lastFrame < 33 || !listening) return;
      lastFrame = t;
      const s = t / 1000;
      for (let i = 0; i < BAR_COUNT; i++) {
        const env = Math.sin((i / (BAR_COUNT - 1)) * Math.PI);
        const v = Math.abs(
          Math.sin(s * 2.1 + i * 0.55) * 0.6 + Math.sin(s * 3.7 + i * 0.23) * 0.4
        );
        bars[i].style.height = (8 + env * (18 + 88 * v)).toFixed(1) + "px";
      }
    };
    requestAnimationFrame(drawWave);
  } else {
    flattenWave();
  }

  const makeTyper = (el, text, speed, hold) => {
    if (reduced) {
      el.textContent = text;
      return;
    }
    let i = 0;
    let deleting = false;
    const tick = () => {
      if (!listening) {
        setTimeout(tick, 250);
        return;
      }
      if (!deleting) {
        i++;
        el.textContent = text.slice(0, i);
        if (i >= text.length) {
          deleting = true;
          setTimeout(tick, hold);
          return;
        }
        setTimeout(tick, speed + Math.random() * 55);
      } else {
        i -= 3;
        if (i <= 0) {
          i = 0;
          deleting = false;
          el.textContent = "";
          setTimeout(tick, 600);
          return;
        }
        el.textContent = text.slice(0, i);
        setTimeout(tick, 16);
      }
    };
    setTimeout(tick, 700);
  };
  makeTyper(
    $("heroTranscript"),
    "the quick brown fox jumps over the lazy dog",
    52,
    2600
  );
  makeTyper(
    $("notepadText"),
    "Action items for Thursday: ship the release, email the changelog to the team, and book the demo room for Friday afternoon.",
    38,
    6000
  );

  const render = () => {
    listenCard.setAttribute("aria-pressed", String(listening));
    listenTitle.textContent = listening
      ? "SpeakType is listening…"
      : "SpeakType is paused";
    listenStatus.textContent = listening
      ? "Listening — tap to stop"
      : "Paused — tap to listen";
    for (const d of [listenDot, statusDot]) {
      d.classList.toggle("dot-live", listening);
      d.classList.toggle("dot-idle", !listening);
    }
    if (!listening) flattenWave();
  };
  listenCard.addEventListener("click", () => {
    listening = !listening;
    render();
  });
  render();
})();