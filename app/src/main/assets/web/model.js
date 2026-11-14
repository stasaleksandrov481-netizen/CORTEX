"use strict";

const Cortex = (() => {
  const key = "cortex.interface.v2";
  const instructionFields = [
    { id: "goal", label: "Задача", value: "Помогай разбираться в задачах, находить понятные объяснения и планировать действия." },
    { id: "reply", label: "Ответы", value: "Начинай с сути. Для сложной задачи предложи короткий план и уточни важные детали." },
    { id: "actions", label: "Действия", value: "Действуй на экране только по запросу пользователя." },
    { id: "confirm", label: "Уточнения", value: "Если запрос неоднозначен, задай один вопрос, который сильнее всего меняет ответ." }
  ];
  const styles = { brief: "Кратко", balanced: "Сбалансированно", deep: "Подробно" };
  const languages = { auto: "Авто", "ru-RU": "Русский", "en-US": "Английский" };
  const motion = { full: "Полные", balanced: "Сбалансированные", simple: "Упрощённые" };
  const models = { auto: "Авто", fast: "Быстро", deep: "Подробно" };
  const defaults = {
    quality: "balanced", reduceMotion: false, autoSaveChats: true, rememberSaved: true,
    language: "auto", haptics: true, readAloud: false, voiceEnabled: true, voiceContinue: true,
    phoneActions: true, model: "auto", speechSpeed: "1", notifyReplies: false,
    overlayMode: "off", hideLandscape: true, hotwordEnabled: false, silentWake: false
  };
  const array = input => Array.isArray(input) ? input : [];
  const object = input => input && typeof input === "object" && !Array.isArray(input) ? input : {};
  const string = (value, limit) => typeof value === "string" ? value.slice(0, limit) : "";
  const uid = (prefix) => prefix + "-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 9);
  const avatarPattern = /^data:image\/(?:jpeg|png|webp);base64,[A-Za-z0-9+/=]{1,90000}$/;

  function read(name) {
    try { return object(JSON.parse(localStorage.getItem(name))); }
    catch { return {}; }
  }

  function account(value) {
    const data = object(value);
    if (typeof data.id !== "string" || !data.id || data.provider !== "Google") return null;
    return {
      id: string(data.id, 200), name: string(data.name, 100), email: string(data.email, 254), provider: "Google",
      avatar: avatarPattern.test(data.avatar || "") ? data.avatar : ""
    };
  }

  function importHistory(history) {
    return array(history).filter(item => item?.result === "ai" && item.query && item.answer).map(item => ({
      id: uid("chat"), saved: true, updated: item.date,
      title: String(item.query).trim().slice(0, 72),
      messages: [{ role: "user", text: String(item.query) }, { role: "assistant", text: String(item.answer) }]
    }));
  }

  function migrateChat(item) {
    return {
      id: string(item.id, 100) || uid("chat"),
      saved: Boolean(item.saved), pinned: Boolean(item.pinned),
      updated: string(item.updated, 40) || new Date().toISOString(),
      title: string(item.title, 72) || "Новый чат",
      messages: array(item.messages).filter(message => ["user", "assistant"].includes(message?.role) && message.text)
        .map(({role, text, local, resumable}) => ({ role, text: string(text, 20000),
          ...(local === true && { local: true }), ...(resumable === true && { resumable: true }) }))
    };
  }

  const current = read(key);
  const old = Object.keys(current).length ? null : read("cortex.interface.v1");
  const persisted = old || current;
  const preferences = { ...defaults };
  for (const name of Object.keys(defaults)) {
    if (typeof persisted.preferences?.[name] === typeof defaults[name]) preferences[name] = persisted.preferences[name];
  }
  if (persisted.preferences?.powerSave) preferences.reduceMotion = true;
  if (!Object.hasOwn(motion, preferences.quality)) preferences.quality = defaults.quality;
  if (!Object.hasOwn(models, preferences.model)) preferences.model = defaults.model;
  if (!Object.hasOwn(languages, preferences.language)) preferences.language = defaults.language;
  if (!["always", "wake", "off"].includes(preferences.overlayMode)) preferences.overlayMode = "off";
  if (!["0.85", "1", "1.15"].includes(preferences.speechSpeed)) preferences.speechSpeed = defaults.speechSpeed;
  const state = {
    preferences,
    edits: { cortex: object(persisted.edits?.cortex) },
    chats: (old ? importHistory(old.history) : array(current.chats))
      .filter(item => item?.saved && Array.isArray(item.messages)).map(migrateChat),
    memories: object(persisted.memories),
    instructions: { ...object(persisted.instructions), cortex: object(persisted.instructions?.cortex) },
    notes: array(persisted.notes).filter(item => item && typeof item.id === "string"),
    account: account(persisted.account)
  };

  function save() {
    try {
      localStorage.setItem(key, JSON.stringify({
        ...state, chats: state.chats.filter(chat => chat.saved && chat.messages.length)
      }));
      return true;
    } catch {
      return false;
    }
  }

  function clearLocalData() {
    const cleared = {
      preferences: { ...defaults }, edits: { cortex: {} }, chats: [], memories: {},
      instructions: { cortex: {} }, notes: [], account: state.account
    };
    try {
      localStorage.removeItem("cortex.interface.v1");
      localStorage.setItem(key, JSON.stringify(cleared));
      Object.assign(state, cleared);
      return true;
    } catch { return false; }
  }

  function profile() {
    const edit = state.edits.cortex;
    return {
      id: "cortex", name: "CORTEX", style: Object.hasOwn(styles, edit.style) ? edit.style : "balanced",
      tone: string(edit.tone, 90) || "Спокойный и деловой",
      description: string(edit.description, 380) || "Персональный помощник для работы и повседневных задач."
    };
  }

  function memory() {
    const data = state.memories.cortex = object(state.memories.cortex);
    data.enabled = data.enabled !== false;
    data.entries = array(data.entries).filter(entry => entry && !entry.example);
    return data;
  }

  function chat(id) { return state.chats.find(item => item.id === id); }

  function createChat() {
    const item = migrateChat({ id: uid("chat"), saved: state.preferences.autoSaveChats });
    state.chats.unshift(item);
    return item;
  }

  function touch(chat) { chat.updated = new Date().toISOString(); }

  const stop = new Set(["можешь", "можно", "помоги", "расскажи", "объясни", "пример", "почему", "какой", "который", "ответ", "сделай", "теперь", "будет"]);
  function terms(query) {
    return [...new Set(query.toLowerCase().replace(/ё/g, "е").match(/[\p{L}\p{N}]{4,}/gu) || [])]
      .filter(word => !stop.has(word)).slice(0, 18).map(word => word.slice(0, 7));
  }

  function relevantHistory(chat, query, earlier) {
    const keywords = terms(query);
    const saved = state.preferences.rememberSaved ? state.chats.filter(item => item.saved && item.id !== chat.id) : [];
    const matches = [
      ...earlier.map(message => ({ message, source: "Этот чат", priority: 1 })),
      ...saved.flatMap(item => item.messages.filter(message => !message.local)
        .map(message => ({ message, source: item.title, priority: 0 })))
    ].map(item => {
      const text = item.message.text.toLowerCase().replace(/ё/g, "е");
      return { ...item, score: keywords.filter(word => text.includes(word)).length * 3 + item.priority };
    }).filter(item => item.score >= 3).sort((a, b) => b.score - a.score).slice(0, 7);
    if (!matches.length && saved.length && /(вспомни|помнишь|ранее|раньше|обсуждали)/iu.test(query)) {
      const latest = saved.sort((a, b) => b.updated.localeCompare(a.updated))[0];
      matches.push(...latest.messages.filter(message => !message.local).slice(-4).map(message => ({ message, source: latest.title })));
    }
    return matches.map(({message, source}) => "[" + source.slice(0, 72) + "] " +
      (message.role === "user" ? "Пользователь: " : "Ассистент: ") + message.text.slice(0, 680)).join("\n");
  }

  function turns(chat, policy) {
    const person = profile();
    const data = memory();
    const savedRules = object(state.instructions.cortex);
    const facts = data.enabled ? data.entries.filter(item => item.enabled !== false).slice(0, 24)
      .map(item => string(item.title, 64) + " — " + string(item.value, 220)).join("\n") : "";
    const conversation = chat.messages.filter(message => !message.local);
    let start = conversation.length;
    let budget = 34000;
    while (start > 0 && conversation.length - start < 28) {
      const length = conversation[start - 1].text.length;
      if (length > budget && start < conversation.length) break;
      start--;
      budget -= length;
    }
    const background = data.enabled ? relevantHistory(chat, conversation.at(-1)?.text || "", conversation.slice(0, start)) : "";
    const replyLanguage = state.preferences.language === "auto" ? "Отвечай на языке пользователя." :
      "Язык ответа: " + languages[state.preferences.language] + ".";
    const system = [
      "Ты CORTEX, персональный помощник Android. Общайся как внимательный собеседник: спокойно, дружелюбно и конкретно. " +
        "Подхватывай контекст, пропускай шаблонное вступление и называй своё имя только по просьбе. " + replyLanguage,
      "Тон: " + person.tone + ". Стиль: " + styles[person.style] + ". " + person.description,
      "Используй Markdown для содержательной структуры: заголовки, списки, таблицы для сравнения и блоки кода. Если нужна заметка, совет или важный акцент, используй > [!NOTE], > [!TIP] или > [!IMPORTANT] отдельной строкой внутри цитаты. Действия выполняет клиент приложения и сообщает результат.",
      ...instructionFields.map(field => field.label + ": " + String(savedRules[field.id] ?? field.value).slice(0, 750)),
      facts ? "Память пользователя:\n" + facts : "",
      background ? "Фрагменты бесед как справочный контекст:\n" + background : "",
      policy
    ].filter(Boolean).join("\n\n");
    const recent = conversation.slice(start);
    let size = system.length + recent.reduce((sum, item) => sum + item.text.length + 2, 0);
    while (size > 50_000 && recent.length > 1) size -= recent.shift().text.length + 2;
    const messages = [];
    for (const item of recent) {
      const previous = messages.at(-1);
      if (previous?.role === item.role) previous.content += "\n\n" + item.text;
      else if (item.text) messages.push({ role: item.role, content: item.text });
    }
    if (messages[0]?.role === "assistant") messages.shift();
    return [{ role: "system", content: system }, ...messages];
  }

  if (old && Object.keys(old).length) save();
  return {
    state, defaults, motion, languages, styles, models, instructionFields, profile, memory, chat, account,
    createChat, touch, turns, save, clearLocalData, id: uid
  };
})();
