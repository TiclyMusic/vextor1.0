#!/usr/bin/env python3
"""
Fine-tuning LoRA di un modello coder piccolo (default Qwen2.5-Coder-1.5B-Instruct)
sul dataset prodotto da scraper/build_dataset.py.

Richiede una GPU (Colab T4 gratuita va bene per 1.5B; L4/A100 per 3B o contesti
più lunghi). Usa solo transformers + peft, senza dipendere da API che cambiano
spesso: la loss è calcolata solo sulla risposta dell'assistente.

Uso:
    python training/train_lora.py --data data/dataset --out out/vextor-lora
    python training/train_lora.py --merge --out out/vextor-lora   # unisce LoRA -> out/vextor-merged
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
from pathlib import Path

import torch
from torch.utils.data import Dataset

DEFAULT_BASE = "Qwen/Qwen2.5-Coder-1.5B-Instruct"


def load_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def as_ids(x) -> list[int]:
    """apply_chat_template può restituire lista, BatchEncoding o dict a seconda della versione."""
    if hasattr(x, "keys"):
        x = x["input_ids"]
    if hasattr(x, "tolist"):
        x = x.tolist()
    if x and isinstance(x[0], list):
        x = x[0]
    return list(x)


class ChatDataset(Dataset):
    """Tokenizza le conversazioni; label = -100 su system/user (loss solo sull'output)."""

    def __init__(self, rows: list[dict], tok, max_len: int):
        self.items = []
        skipped = mismatched = 0
        for r in rows:
            msgs = r["messages"]
            prompt_ids = tok.apply_chat_template(msgs[:-1], tokenize=True, add_generation_prompt=True)
            full_ids = tok.apply_chat_template(msgs, tokenize=True, add_generation_prompt=False)
            prompt_ids, full_ids = as_ids(prompt_ids), as_ids(full_ids)
            if len(full_ids) > max_len:
                skipped += 1
                continue
            if full_ids[: len(prompt_ids)] != prompt_ids:
                mismatched += 1
                continue
            labels = [-100] * len(prompt_ids) + full_ids[len(prompt_ids):]
            self.items.append((full_ids, labels))
        print(f"  esempi: {len(self.items)} (troppo lunghi: {skipped}, template incoerente: {mismatched})")

    def __len__(self):
        return len(self.items)

    def __getitem__(self, i):
        ids, labels = self.items[i]
        return {"input_ids": ids, "labels": labels}


def collate(pad_id: int):
    def fn(batch):
        n = max(len(b["input_ids"]) for b in batch)
        ids = torch.full((len(batch), n), pad_id, dtype=torch.long)
        lab = torch.full((len(batch), n), -100, dtype=torch.long)
        att = torch.zeros((len(batch), n), dtype=torch.long)
        for i, b in enumerate(batch):
            L = len(b["input_ids"])
            ids[i, :L] = torch.tensor(b["input_ids"])
            lab[i, :L] = torch.tensor(b["labels"])
            att[i, :L] = 1
        return {"input_ids": ids, "labels": lab, "attention_mask": att}
    return fn


def training_args(**kw):
    """TrainingArguments ignorando le opzioni non supportate dalla versione installata."""
    import inspect

    from transformers import TrainingArguments
    accepted = inspect.signature(TrainingArguments.__init__).parameters
    dropped = [k for k in kw if k not in accepted]
    if dropped:
        print(f"  (opzioni non supportate da questa versione di transformers: {dropped})")
    return TrainingArguments(**{k: v for k, v in kw.items() if k in accepted})


def train(args):
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, Trainer

    tok = AutoTokenizer.from_pretrained(args.base)
    if tok.pad_token is None:
        tok.pad_token = tok.eos_token

    data = Path(args.data)
    train_rows = load_jsonl(data / "train.jsonl")
    val_rows = load_jsonl(data / "val.jsonl")
    if args.limit:
        random.Random(0).shuffle(train_rows)
        train_rows = train_rows[: args.limit]
    print(f"Tokenizzo {len(train_rows)} esempi di train, {len(val_rows)} di val...")
    train_ds = ChatDataset(train_rows, tok, args.max_len)
    val_ds = ChatDataset(val_rows, tok, args.max_len) if val_rows else None

    bf16 = torch.cuda.is_available() and torch.cuda.is_bf16_supported()
    dtype = torch.bfloat16 if bf16 else (torch.float16 if torch.cuda.is_available() else torch.float32)
    model = AutoModelForCausalLM.from_pretrained(
        args.base, dtype=dtype, device_map="auto",
        attn_implementation="sdpa",
    )
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()
    model.config.use_cache = False

    lora = LoraConfig(
        r=args.lora_r, lora_alpha=args.lora_r * 2, lora_dropout=0.05, bias="none",
        task_type="CAUSAL_LM",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    model = get_peft_model(model, lora)
    model.print_trainable_parameters()

    steps_per_epoch = math.ceil(len(train_ds) / (args.batch * args.grad_accum))
    total_steps = max(1, math.ceil(steps_per_epoch * args.epochs))
    eval_every = max(10, steps_per_epoch // 2)
    targs = training_args(
        output_dir=args.out,
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch,
        per_device_eval_batch_size=1,
        gradient_accumulation_steps=args.grad_accum,
        learning_rate=args.lr,
        lr_scheduler_type="cosine",
        warmup_steps=max(1, int(total_steps * 0.03)),
        weight_decay=0.0,
        logging_steps=5,
        save_strategy="steps",
        save_steps=eval_every,
        save_total_limit=2,
        eval_strategy="steps" if val_ds else "no",
        eval_steps=eval_every,
        bf16=bf16,
        fp16=not bf16 and torch.cuda.is_available(),
        optim="adamw_torch",
        group_by_length=True,
        report_to="none",
        remove_unused_columns=False,
        dataloader_num_workers=2,
    )
    trainer = Trainer(
        model=model, args=targs, train_dataset=train_ds, eval_dataset=val_ds,
        data_collator=collate(tok.pad_token_id),
    )
    last = None
    if args.resume and any(Path(args.out).glob("checkpoint-*")):
        last = True
    trainer.train(resume_from_checkpoint=last)
    model.save_pretrained(args.out)
    tok.save_pretrained(args.out)
    (Path(args.out) / "vextor_base.txt").write_text(args.base)
    print(f"LoRA salvata in {args.out}")


def merge(args):
    from peft import PeftModel
    from transformers import AutoModelForCausalLM, AutoTokenizer

    base = args.base
    marker = Path(args.out) / "vextor_base.txt"
    if marker.exists():
        base = marker.read_text().strip()
    merged_dir = args.merged or str(Path(args.out).parent / "vextor-merged")
    print(f"Unisco {args.out} su {base} -> {merged_dir}")
    model = AutoModelForCausalLM.from_pretrained(base, dtype=torch.float16, device_map="cpu")
    model = PeftModel.from_pretrained(model, args.out)
    model = model.merge_and_unload()
    model.save_pretrained(merged_dir, safe_serialization=True)
    AutoTokenizer.from_pretrained(args.out).save_pretrained(merged_dir)
    print("Fatto.")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--data", default="data/dataset")
    ap.add_argument("--out", default="out/vextor-lora")
    ap.add_argument("--max-len", type=int, default=8192, help="token massimi per esempio")
    ap.add_argument("--epochs", type=float, default=2)
    ap.add_argument("--batch", type=int, default=1)
    ap.add_argument("--grad-accum", type=int, default=8)
    ap.add_argument("--lr", type=float, default=2e-4)
    ap.add_argument("--lora-r", type=int, default=32)
    ap.add_argument("--limit", type=int, default=0, help="usa solo N esempi (test veloce)")
    ap.add_argument("--resume", action="store_true")
    ap.add_argument("--merge", action="store_true", help="unisci la LoRA nel modello base")
    ap.add_argument("--merged", default="", help="cartella output del modello unito")
    args = ap.parse_args()
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    if args.merge:
        merge(args)
    else:
        train(args)


if __name__ == "__main__":
    main()
